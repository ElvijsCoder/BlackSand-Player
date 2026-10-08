package com.blacksand.player.playback

import android.Manifest
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.blacksand.player.data.MusicRepository
import com.blacksand.player.data.DotArt
import com.blacksand.player.data.ListeningStats
import com.blacksand.player.data.Settings
import com.blacksand.player.data.SongStat
import com.blacksand.player.data.StatsStore
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
import java.time.LocalDate
import kotlin.math.pow

/**
 * Owns the player. Media3 gives us the notification, lock screen controls,
 * Bluetooth/headphone buttons and foreground-service handling for free.
 * Also remembers the queue and position so playback resumes after the app is killed.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var sleepJob: Job? = null

    // Sound effects attached to the player's audio session.
    private val settings by lazy { Settings.prefs(this) }
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var trackGainDb: Float? = null // ReplayGain of the current track, if tagged
    private var baseVolume = 1f // player volume after normalization; the sleep fade scales this
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applySound() }

    private val tapeProcessor = TapeProcessor()

    // Flip to pause: face down pauses; face up again resumes.
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private var flipListening = false
    private var flipPaused = false
    private var faceDownSince = 0L
    private val flipListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val z = event.values[2]
            val now = SystemClock.elapsedRealtime()
            if (z < -8.5f) {
                if (faceDownSince == 0L) faceDownSince = now
                if (player.isPlaying && now - faceDownSince > 700) {
                    player.pause()
                    flipPaused = true
                }
            } else {
                faceDownSince = 0L
                if (z > 5f && flipPaused) {
                    flipPaused = false
                    player.play()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    // Listening stats: a play counts once 30 s (or half a short song) has been heard.
    private val statsStore by lazy { StatsStore(this) }
    private var stats: ListeningStats? = null
    private var listenedThisItem = 0L
    private var countedThisItem = false

    // Fade between songs.
    private var fadeFactor = 1f
    private var fadeInStart: Long? = null
    private var lastAlbum: CharSequence? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        // Tape mode sits in the audio pipeline permanently; it passes sound through untouched when off.
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(tapeProcessor))
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
        player = ExoPlayer.Builder(this, renderers)
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

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                listenedThisItem = 0
                countedThisItem = false
                // Fade the new song in after an automatic change, unless it continues the same album.
                val album = mediaItem?.mediaMetadata?.albumTitle
                val sameAlbum = album != null && album == lastAlbum
                fadeInStart = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !sameAlbum) SystemClock.elapsedRealtime() else null
                lastAlbum = album
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveStats()
                if (isPlaying) flipPaused = false
                updateFlipSensor()
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) = setupEffects()
            override fun onTracksChanged(tracks: Tracks) {
                trackGainDb = replayGainOf(tracks)
                applyGain()
            }
        })
        setupEffects()
        settings.registerOnSharedPreferenceChangeListener(settingsListener)
        scope.launch { stats = statsStore.load() }

        // Fade loop: cheap check, faster only while a fade could be happening.
        scope.launch {
            while (isActive) {
                val active = applyFade()
                delay(if (active) 100 else 500)
            }
        }
        restoreQueue(player)

        // Position changes constantly; save it every few seconds while playing.
        // Every 30 s it also refreshes the widget's progress bar and tape amount.
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) {
                    prefs.edit { putLong(KEY_POSITION, player.currentPosition) }
                    addListen(5_000)
                    if (++ticks % 6 == 0) {
                        pushWidget()
                        saveStats()
                    }
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
        settings.unregisterOnSharedPreferenceChangeListener(settingsListener)
        releaseEffects()
        flipPaused = false
        updateFlipSensor(forceOff = true)
        stats?.let { statsStore.write(statsStore.toJson(it)) } // small file; written directly while shutting down
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
        player.volume = baseVolume
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
                        player.volume = baseVolume * i / steps.toFloat()
                        delay(FADE_MS / steps)
                    }
                    player.pause()
                    player.volume = baseVolume
                    prefs.edit { putLong(KEY_SLEEP_END, 0) }
                }
            }
        }
    }

    // --- Sound: equalizer, bass boost, ReplayGain -------------------------------------

    private fun setupEffects() {
        releaseEffects()
        val session = player.audioSessionId
        if (session == C.AUDIO_SESSION_ID_UNSET) return
        equalizer = runCatching { Equalizer(0, session) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, session) }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(session) }.getOrNull()
        // Tell the settings screen what this phone's equalizer offers.
        equalizer?.let { eq ->
            val bands = (0 until eq.numberOfBands).map { eq.getCenterFreq(it.toShort()) / 1000 }
            settings.edit {
                putString(Settings.EQ_BANDS, bands.joinToString(","))
                putInt(Settings.EQ_MIN, eq.bandLevelRange[0].toInt())
                putInt(Settings.EQ_MAX, eq.bandLevelRange[1].toInt())
            }
        }
        applySound()
    }

    private fun releaseEffects() {
        equalizer?.release(); equalizer = null
        bassBoost?.release(); bassBoost = null
        loudness?.release(); loudness = null
    }

    /** The flip sensor only runs while playing, or while waiting to resume after a flip. */
    private fun updateFlipSensor(forceOff: Boolean = false) {
        val want = !forceOff && settings.getBoolean(Settings.FLIP_PAUSE, false) && (player.isPlaying || flipPaused)
        if (want == flipListening) return
        val gravity = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (want && gravity != null) {
            sensors?.registerListener(flipListener, gravity, SensorManager.SENSOR_DELAY_NORMAL)
            flipListening = true
        } else if (!want) {
            sensors?.unregisterListener(flipListener)
            flipListening = false
            faceDownSince = 0L
        }
    }

    private fun applySound() {
        tapeProcessor.amount = settings.getInt(Settings.TAPE_MODE, 0)
        updateFlipSensor()
        if (!settings.getBoolean(Settings.FLIP_PAUSE, false)) flipPaused = false
        equalizer?.let { eq ->
            runCatching {
                val levels = settings.getString(Settings.EQ_LEVELS, null)?.split(",")?.mapNotNull { it.toIntOrNull() }
                levels?.forEachIndexed { i, level -> if (i < eq.numberOfBands) eq.setBandLevel(i.toShort(), level.toShort()) }
                eq.enabled = settings.getBoolean(Settings.EQ_ON, false)
            }
        }
        bassBoost?.let { bb ->
            runCatching {
                val strength = settings.getInt(Settings.BASS, 0)
                if (bb.strengthSupported) bb.setStrength(strength.toShort())
                bb.enabled = strength > 0
            }
        }
        applyGain()
    }

    /**
     * Volume normalization from ReplayGain tags: quieter via player volume, louder (up to +6 dB)
     * via the loudness enhancer. Untagged tracks play as they are.
     */
    private fun applyGain() {
        val gain = trackGainDb?.takeIf { settings.getBoolean(Settings.NORMALIZE, false) }
        baseVolume = if (gain != null && gain < 0) 10f.pow(gain / 20f) else 1f
        loudness?.let { le ->
            runCatching {
                val boost = if (gain != null && gain > 0) (minOf(gain, 6f) * 100).toInt() else 0
                le.setTargetGain(boost)
                le.enabled = boost > 0
            }
        }
        if (sleepJob?.isActive != true) player.volume = baseVolume * fadeFactor
    }

    /**
     * Fade between songs: the last few seconds fade out and the next song fades in, like the tape
     * running into the next track. Skipped within an album so albums stay gapless.
     * Returns true while a fade is possible, so the loop checks more often.
     */
    private fun applyFade(): Boolean {
        val fadeMs = settings.getInt(Settings.FADE_SECONDS, 0) * 1000L
        if (fadeMs == 0L || sleepJob?.isActive == true || !player.isPlaying) {
            if (fadeFactor != 1f && sleepJob?.isActive != true) {
                fadeFactor = 1f
                player.volume = baseVolume
            }
            return false
        }
        var f = 1f
        val duration = player.duration
        if (duration > 0 && player.hasNextMediaItem() && !nextIsSameAlbum()) {
            val left = duration - player.currentPosition
            if (left < fadeMs) f = (left.toFloat() / fadeMs).coerceIn(0f, 1f)
        }
        fadeInStart?.let { start ->
            val elapsed = SystemClock.elapsedRealtime() - start
            if (elapsed < fadeMs) f = minOf(f, elapsed.toFloat() / fadeMs) else fadeInStart = null
        }
        if (f != fadeFactor) {
            fadeFactor = f
            player.volume = baseVolume * f
        }
        return true
    }

    private fun nextIsSameAlbum(): Boolean {
        val next = player.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return false
        val album = player.currentMediaItem?.mediaMetadata?.albumTitle ?: return false
        return player.getMediaItemAt(next).mediaMetadata.albumTitle == album
    }

    // --- Listening stats ---------------------------------------------------------------

    private fun addListen(ms: Long) {
        val s = stats ?: return
        val id = player.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val stat = s.songs.getOrPut(id) { SongStat() }
        stat.listenedMs += ms
        val today = LocalDate.now().toString()
        s.days[today] = (s.days[today] ?: 0L) + ms
        listenedThisItem += ms
        val threshold = minOf(30_000L, player.duration.coerceAtLeast(0L) / 2)
        if (!countedThisItem && listenedThisItem >= threshold) {
            stat.plays++
            stat.lastPlayed = System.currentTimeMillis()
            countedThisItem = true
        }
    }

    private fun saveStats() {
        val s = stats ?: return
        val json = statsStore.toJson(s)
        scope.launch(Dispatchers.IO) { statsStore.write(json) }
    }

    // ReplayGain lives in ID3 TXXX frames or Vorbis comments; both print as "...REPLAYGAIN_TRACK_GAIN...-6.5 dB".
    private fun replayGainOf(tracks: Tracks): Float? {
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
            for (i in 0 until group.length) {
                val metadata = group.getTrackFormat(i).metadata ?: continue
                for (e in 0 until metadata.length()) {
                    val match = REPLAY_GAIN.find(metadata.get(e).toString()) ?: continue
                    return match.groupValues[1].toFloatOrNull()
                }
            }
        }
        return null
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
        val dots = settings.getBoolean(Settings.DOT_ART, true)
        val key = "${player.currentMediaItem?.mediaId}:${meta.artworkData?.size}:$dots"
        if (key != widgetArtKey) {
            widgetArtKey = key
            widgetArt = meta.artworkData?.let { bytes ->
                greyThumb(bytes)?.let { if (dots) DotArt.render(it, cells = 16, sizePx = 160) else it }
            }
        }
        val sideB = prefs.getInt(KEY_TAPE_SIDE_B, -1)
        val state = WidgetState(
            title = meta.title?.toString(),
            artist = meta.artist?.toString(),
            playing = player.isPlaying && !stopped,
            positionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0L),
            track = player.currentMediaItemIndex + 1,
            count = player.mediaItemCount,
            art = widgetArt,
            side = if (sideB >= 0 && player.currentMediaItemIndex >= sideB) "B" else "A",
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
        /** Queue index where Side B of the playing mixtape starts, or -1. Written by the UI. */
        const val KEY_TAPE_SIDE_B = "tapeSideB"
        private val REPLAY_GAIN = Regex("REPLAYGAIN_TRACK_GAIN\\D*?([-+]?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
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
