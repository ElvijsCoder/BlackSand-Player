package com.blacksand.player.playback

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.blacksand.player.data.MusicRepository
import com.blacksand.player.data.toMediaItem
import com.blacksand.player.widget.CassetteWidget
import com.blacksand.player.widget.WidgetState
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    private lateinit var player: ExoPlayer
    private var sleepJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
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
                if (events.containsAny(
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_TIMELINE_CHANGED,
                    )
                ) pushWidget()
            }

            // "End of song" sleep: ExoPlayer paused at the end of the track, so the timer is done.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    this@PlaybackService.player.pauseAtEndOfMediaItems = false
                    prefs.edit { putLong(KEY_SLEEP_END, 0) }
                }
            }
        })
        prefs.edit { putLong(KEY_SLEEP_END, 0) } // a fresh service has no timer running
        restoreQueue(player)

        // Position changes constantly; save it every few seconds while playing.
        // Every 30 s it also refreshes the widget's progress bar and tape amount.
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) {
                    prefs.edit { putLong(KEY_POSITION, player.currentPosition) }
                    if (++ticks % 6 == 0) pushWidget()
                }
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
        pushWidget(stopped = true)
        mediaSession?.run {
            saveQueue(player)
            player.release()
            release()
        }
        mediaSession = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Sleep timer. [minutes] > 0 counts down then fades out over [FADE_MS] and pauses,
     * -1 pauses at the end of the current song, 0 cancels.
     * The end time goes in prefs so the UI can show what's left.
     */
    private fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        player.volume = 1f
        player.pauseAtEndOfMediaItems = false
        when {
            minutes == 0 -> prefs.edit { putLong(KEY_SLEEP_END, 0) }
            minutes < 0 -> {
                player.pauseAtEndOfMediaItems = true
                prefs.edit { putLong(KEY_SLEEP_END, -1) }
            }
            else -> {
                val end = System.currentTimeMillis() + minutes * 60_000L
                prefs.edit { putLong(KEY_SLEEP_END, end) }
                sleepJob = scope.launch {
                    delay(end - System.currentTimeMillis() - FADE_MS)
                    val steps = 20
                    for (i in steps downTo 0) {
                        player.volume = i / steps.toFloat()
                        delay(FADE_MS / steps)
                    }
                    player.pause()
                    player.volume = 1f
                    prefs.edit { putLong(KEY_SLEEP_END, 0) }
                }
            }
        }
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

    /** The last saved queue and spot, or null. Songs deleted since are skipped. */
    private suspend fun loadSavedQueue(): MediaSession.MediaItemsWithStartPosition? {
        val saved = prefs.getString(KEY_QUEUE, null) ?: return null
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val byId = MusicRepository(this).loadSongs().associateBy { it.id.toString() }
        val savedIds = saved.split(",")
        val items = savedIds.mapNotNull { byId[it]?.toMediaItem() }
        if (items.isEmpty()) return null
        val currentId = savedIds.getOrNull(prefs.getInt(KEY_INDEX, 0))
        val found = items.indexOfFirst { it.mediaId == currentId }
        val position = if (found >= 0) prefs.getLong(KEY_POSITION, 0) else 0L
        return MediaSession.MediaItemsWithStartPosition(items, found.coerceAtLeast(0), position)
    }

    /** Loads the last queue, paused at the saved spot, unless something is already playing. */
    private fun restoreQueue(player: Player) {
        scope.launch {
            val saved = loadSavedQueue() ?: return@launch
            if (player.mediaItemCount > 0) return@launch // user already started something
            player.shuffleModeEnabled = prefs.getBoolean(KEY_SHUFFLE, false)
            player.repeatMode = prefs.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)
            player.setMediaItems(saved.mediaItems, saved.startIndex, saved.startPositionMs)
            player.prepare()
        }
    }

    // --- Widget ------------------------------------------------------------------------

    private var widgetArtKey: String? = null
    private var widgetArt: Bitmap? = null

    private fun pushWidget(stopped: Boolean = false) {
        val meta = player.mediaMetadata
        val key = "${player.currentMediaItem?.mediaId}:${meta.artworkData?.size}"
        if (key != widgetArtKey) {
            widgetArtKey = key
            widgetArt = meta.artworkData?.let { greyThumb(it) }
        }
        val state = WidgetState(
            title = meta.title?.toString(),
            artist = meta.artist?.toString(),
            playing = player.isPlaying && !stopped,
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0L),
            track = player.currentMediaItemIndex + 1,
            count = player.mediaItemCount,
            art = widgetArt,
        )
        CassetteWidget.push(this, state)
        if (state.title != null) CassetteWidget.saveSnapshot(this, state)
    }

    /** Cover art shrunk to ~256 px and turned greyscale, to match the cassette label. */
    private fun greyThumb(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private inner class SessionCallback : MediaSession.Callback {
        // Allow our own sleep-timer command on top of the standard playback commands.
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val default = super.onConnect(session, controller)
            return MediaSession.ConnectionResult.accept(
                default.availableSessionCommands.buildUpon().add(SessionCommand(CMD_SLEEP, Bundle.EMPTY)).build(),
                default.availablePlayerCommands,
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_SLEEP) setSleep(args.getInt(ARG_MINUTES))
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // A widget key or headphone button pressed while nothing is loaded: pick up where we left off.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val saved = loadSavedQueue()
                if (saved != null) future.set(saved) else future.setException(UnsupportedOperationException("Nothing to resume"))
            }
            return future
        }

        // Media3 strips file URIs from items sent by a controller, so restore them here.
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

    companion object {
        const val PREFS = "playback"
        const val KEY_SLEEP_END = "sleepEnd"
        const val CMD_SLEEP = "blacksand.SLEEP"
        const val ARG_MINUTES = "minutes"
        private const val FADE_MS = 10_000L
        private const val KEY_QUEUE = "queue"
        private const val KEY_INDEX = "index"
        private const val KEY_POSITION = "position"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT = "repeat"
    }
}
