package com.blacksand.player.playback

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.blacksand.player.data.MusicRepository
import com.blacksand.player.data.toMediaItem
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the player. Media3 gives us the notification, lock screen controls,
 * Bluetooth/headphone buttons and foreground-service handling for free.
 * Also remembers the queue and position so playback resumes after the app is killed.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("playback", Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // pause for calls, duck for navigation
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones unplug
            .build()

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()

        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED,
                    )
                ) saveQueue(player)
            }
        })
        restoreQueue(player)

        // Position changes constantly; save it every few seconds while playing.
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) prefs.edit { putLong(KEY_POSITION, player.currentPosition) }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    // Swiping the app away stops the service only if nothing is playing.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        saveQueue(player)
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            saveQueue(player)
            player.release()
            release()
        }
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    private fun saveQueue(player: Player) {
        if (player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).joinToString(",") { player.getMediaItemAt(it).mediaId }
        prefs.edit {
            putString(KEY_QUEUE, ids)
            putInt(KEY_INDEX, player.currentMediaItemIndex)
            putLong(KEY_POSITION, player.currentPosition)
            putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
            putInt(KEY_REPEAT, player.repeatMode)
        }
    }

    /** Loads the last queue, paused at the saved spot. Songs deleted since are skipped. */
    private fun restoreQueue(player: Player) {
        val saved = prefs.getString(KEY_QUEUE, null) ?: return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        scope.launch {
            val byId = MusicRepository(this@PlaybackService).loadSongs().associateBy { it.id.toString() }
            val savedIds = saved.split(",")
            val items = savedIds.mapNotNull { byId[it]?.toMediaItem() }
            if (items.isEmpty() || player.mediaItemCount > 0) return@launch // user already started something
            val savedIndex = prefs.getInt(KEY_INDEX, 0)
            val currentId = savedIds.getOrNull(savedIndex)
            val found = items.indexOfFirst { it.mediaId == currentId }
            val index = found.coerceAtLeast(0)
            val position = if (found >= 0) prefs.getLong(KEY_POSITION, 0) else 0L
            player.shuffleModeEnabled = prefs.getBoolean(KEY_SHUFFLE, false)
            player.repeatMode = prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)
            player.setMediaItems(items, index, position)
            player.prepare()
        }
    }

    // Media3 strips file URIs from items sent by a controller, so restore them here.
    private class SessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(
                mediaItems.map { it.buildUpon().setUri(it.requestMetadata.mediaUri).build() }
                    .toMutableList()
            )
    }

    private companion object {
        const val KEY_QUEUE = "queue"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat"
    }
}
