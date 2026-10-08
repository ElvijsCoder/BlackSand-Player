package com.blacksand.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.blacksand.player.data.DotArt
import com.blacksand.player.data.ListeningStats
import com.blacksand.player.data.MusicRepository
import com.blacksand.player.data.Playlist
import com.blacksand.player.data.PlaylistStore
import com.blacksand.player.data.Settings
import com.blacksand.player.data.parseM3u
import com.blacksand.player.data.Song
import com.blacksand.player.data.StatsStore
import com.blacksand.player.data.sidesOf
import com.blacksand.player.data.toMediaItem
import com.blacksand.player.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val title: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val currentMediaId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val trackCount: Int = 0,
    val artwork: ImageBitmap? = null,
    /** 1 while fast-forwarding, -1 while rewinding, 0 otherwise. */
    val scanDirection: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** Which way the cassette swap slides: 1 = forward, -1 = back. */
    val swapDirection: Int = 1,
    /** Sleep timer: 0 = off, -1 = end of song, otherwise the wall-clock time it stops. */
    val sleepEnd: Long = 0L,
    /** Which side of the tape is playing: "B" once a mixtape reaches its second side. */
    val side: String = "A",
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Everything the settings screen shows. Band info comes from the phone's equalizer via the service. */
data class SettingsState(
    val eqOn: Boolean = false,
    val eqBands: List<Int> = emptyList(), // centre frequencies, Hz
    val eqLevels: List<Int> = emptyList(), // millibels
    val eqMin: Int = -1500,
    val eqMax: Int = 1500,
    val bass: Int = 0, // 0..1000
    val normalize: Boolean = false,
    val fadeSeconds: Int = 0,
    val tapeMode: Int = 0,
    val soundFx: Boolean = true,
    val flipPause: Boolean = false,
    val dotArt: Boolean = true,
    val skipShort: Boolean = true,
    val excluded: Set<String> = emptySet(),
    val folders: List<Pair<String, Int>> = emptyList(), // every music folder with its song count
)

/** One entry in the queue view, in play order. [index] is its position in the player's list. */
data class QueueEntry(val index: Int, val title: String, val artist: String)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = MusicRepository(app)
    private val store = PlaylistStore(app)
    private val saveLock = Mutex()
    private val prefs = app.getSharedPreferences(PlaybackService.PREFS, Context.MODE_PRIVATE)

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists = _playlists.asStateFlow()

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())
    val queue = _queue.asStateFlow()

    private var sleepOption = 0

    private val statsStore = StatsStore(app)
    private val _stats = MutableStateFlow<ListeningStats?>(null)
    val stats = _stats.asStateFlow()

    /** When a mixtape is playing: the queue index where Side B starts. */
    private var sideBStart: Int? = prefs.getInt(PlaybackService.KEY_TAPE_SIDE_B, -1).takeIf { it >= 0 }

    private val settingsPrefs = Settings.prefs(app)
    private val _settings = MutableStateFlow(SettingsState())
    val settings = _settings.asStateFlow()
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshSettings() }

    /** Fires when something outside the UI (a home screen shortcut) wants the cassette screen open. */
    private val _openPlayer = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val openPlayer = _openPlayer.asSharedFlow()
    private var pendingShortcut: String? = null

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui = _ui.asStateFlow()

    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(
        app, SessionToken(app, ComponentName(app, PlaybackService::class.java))
    ).buildAsync()

    // The direction of the last skip the user asked for; automatic advances always go forward.
    private var pendingDirection = 1
    private var swapDirection = 1

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            swapDirection = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) pendingDirection else 1
        }
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                _queue.value = queueOf(player)
            }
        }
    }

    private var ticker: Job? = null
    private var scanJob: Job? = null
    private var resumeAfterScan = false
    private var artKey: String? = null
    private var art: ImageBitmap? = null

    init {
        controllerFuture.addListener({
            controller = controllerFuture.get().also {
                it.addListener(listener)
                publish(it)
                _queue.value = queueOf(it)
                runPendingShortcut()
            }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch { _playlists.value = store.load() }
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsListener)
        refreshSettings()
    }

    fun loadLibrary() {
        viewModelScope.launch {
            _songs.value = repository.loadSongs()
            val all = repository.loadSongs(filtered = false)
            _settings.update { s -> s.copy(folders = all.groupingBy { it.folder }.eachCount().toList().sortedBy { it.first.lowercase() }) }
            runPendingShortcut()
        }
    }

    // --- Settings ----------------------------------------------------------------------

    private fun refreshSettings() {
        val p = settingsPrefs
        val bands = p.getString(Settings.EQ_BANDS, null)?.split(",")?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val saved = p.getString(Settings.EQ_LEVELS, null)?.split(",")?.mapNotNull { it.toIntOrNull() }.orEmpty()
        _settings.update {
            it.copy(
                eqOn = p.getBoolean(Settings.EQ_ON, false),
                eqBands = bands,
                eqLevels = List(bands.size) { i -> saved.getOrElse(i) { 0 } },
                eqMin = p.getInt(Settings.EQ_MIN, -1500),
                eqMax = p.getInt(Settings.EQ_MAX, 1500),
                bass = p.getInt(Settings.BASS, 0),
                normalize = p.getBoolean(Settings.NORMALIZE, false),
                fadeSeconds = p.getInt(Settings.FADE_SECONDS, 0),
                tapeMode = p.getInt(Settings.TAPE_MODE, 0),
                soundFx = p.getBoolean(Settings.SOUND_FX, true),
                flipPause = p.getBoolean(Settings.FLIP_PAUSE, false),
                dotArt = p.getBoolean(Settings.DOT_ART, true),
                skipShort = p.getBoolean(Settings.SKIP_SHORT, true),
                excluded = p.getStringSet(Settings.EXCLUDED, emptySet()).orEmpty(),
            )
        }
    }

    fun setEqOn(on: Boolean) = settingsPrefs.edit { putBoolean(Settings.EQ_ON, on) }
    fun setBass(strength: Int) = settingsPrefs.edit { putInt(Settings.BASS, strength.coerceIn(0, 1000)) }
    fun setNormalize(on: Boolean) = settingsPrefs.edit { putBoolean(Settings.NORMALIZE, on) }

    /** Off → light → worn → off. */
    fun cycleTapeMode() = settingsPrefs.edit { putInt(Settings.TAPE_MODE, (_settings.value.tapeMode + 1) % 3) }
    fun setSoundFx(on: Boolean) = settingsPrefs.edit { putBoolean(Settings.SOUND_FX, on) }
    fun setFlipPause(on: Boolean) = settingsPrefs.edit { putBoolean(Settings.FLIP_PAUSE, on) }

    fun setDotArt(on: Boolean) {
        settingsPrefs.edit { putBoolean(Settings.DOT_ART, on) }
        _settings.update { it.copy(dotArt = on) }
        controller?.let { publish(it) } // redraw the current cover in the new style
    }

    /** Off → 2 → 4 → 6 seconds → off. */
    fun cycleFade() {
        val next = when (_settings.value.fadeSeconds) { 0 -> 2; 2 -> 4; 4 -> 6; else -> 0 }
        settingsPrefs.edit { putInt(Settings.FADE_SECONDS, next) }
    }

    fun setEqLevel(band: Int, level: Int) {
        val levels = _settings.value.eqLevels.toMutableList()
        if (band !in levels.indices) return
        levels[band] = level
        settingsPrefs.edit { putString(Settings.EQ_LEVELS, levels.joinToString(",")) }
    }

    fun resetEq() = settingsPrefs.edit { putString(Settings.EQ_LEVELS, _settings.value.eqLevels.joinToString(",") { "0" }) }

    fun setSkipShort(on: Boolean) {
        settingsPrefs.edit { putBoolean(Settings.SKIP_SHORT, on) }
        loadLibrary()
    }

    fun setFolderHidden(folder: String, hidden: Boolean) {
        val set = _settings.value.excluded.toMutableSet()
        if (hidden) set += folder else set -= folder
        settingsPrefs.edit { putStringSet(Settings.EXCLUDED, set) }
        loadLibrary()
    }

    // --- Home screen shortcuts ---------------------------------------------------------

    /** Runs a launcher shortcut once both the player and the library are ready. */
    fun handleShortcut(action: String?) {
        if (action == ACTION_SHUFFLE_ALL || action == ACTION_RESUME) {
            pendingShortcut = action
            runPendingShortcut()
        }
    }

    private fun runPendingShortcut() {
        val action = pendingShortcut ?: return
        val c = controller ?: return
        when (action) {
            ACTION_SHUFFLE_ALL -> {
                val all = _songs.value
                if (all.isEmpty()) return // library not loaded yet; try again when it is
                c.shuffleModeEnabled = true
                play(all, all.indices.random())
            }
            ACTION_RESUME -> if (!c.isPlaying) c.play()
        }
        pendingShortcut = null
        _openPlayer.tryEmit(Unit)
    }

    /** Plays [list] as the queue, starting at [index]. */
    fun play(list: List<Song>, index: Int) {
        val c = controller ?: return
        pendingDirection = 1
        setSideB(null)
        c.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    /** Inserts [song] right after the current one (or just plays it if nothing is loaded). */
    fun playNext(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) play(listOf(song), 0)
        else c.addMediaItem(c.currentMediaItemIndex + 1, song.toMediaItem())
    }

    fun addToQueue(song: Song) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) play(listOf(song), 0) else c.addMediaItem(song.toMediaItem())
    }

    fun jumpTo(index: Int) {
        val c = controller ?: return
        pendingDirection = if (index < c.currentMediaItemIndex) -1 else 1
        c.seekToDefaultPosition(index)
        c.play()
    }

    fun removeFromQueue(index: Int) { controller?.removeMediaItem(index) }
    fun moveInQueue(from: Int, to: Int) { controller?.moveMediaItem(from, to) }

    // The queue in the order it will actually play (differs from list order when shuffled).
    private fun queueOf(p: Player): List<QueueEntry> {
        val t = p.currentTimeline
        if (t.isEmpty) return emptyList()
        val out = ArrayList<QueueEntry>(t.windowCount)
        var i = t.getFirstWindowIndex(p.shuffleModeEnabled)
        while (i != C.INDEX_UNSET) {
            val m = p.getMediaItemAt(i).mediaMetadata
            out += QueueEntry(i, m.title?.toString() ?: "", m.artist?.toString() ?: "")
            i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
        }
        return out
    }

    // --- Playlists ---------------------------------------------------------------------

    private fun editPlaylists(change: (List<Playlist>) -> List<Playlist>) {
        _playlists.value = change(_playlists.value)
        viewModelScope.launch { saveLock.withLock { store.save(_playlists.value) } }
    }

    fun createPlaylist(name: String, songs: List<Song> = emptyList()) {
        editPlaylists { it + Playlist(System.currentTimeMillis(), name.trim(), songs.map { s -> s.id }) }
    }

    fun renamePlaylist(id: Long, name: String) {
        editPlaylists { list -> list.map { if (it.id == id) it.copy(name = name.trim()) else it } }
    }

    fun deletePlaylist(id: Long) {
        editPlaylists { list -> list.filterNot { it.id == id } }
    }

    fun createMixtape(name: String, tapeMinutes: Int) {
        editPlaylists { it + Playlist(System.currentTimeMillis(), name.trim(), emptyList(), tapeMinutes) }
    }

    /** Adds [song]; returns false if it's a mixtape and the song doesn't fit on the tape. */
    fun addToPlaylist(id: Long, song: Song): Boolean {
        val playlist = _playlists.value.firstOrNull { it.id == id } ?: return false
        if (song.id in playlist.songIds) return true
        playlist.tapeMinutes?.let { minutes ->
            val byId = _songs.value.associateBy { it.id }
            val songs = playlist.songIds.mapNotNull { byId[it] } + song
            if (sidesOf(songs, minutes) == null) return false
        }
        editPlaylists { list -> list.map { if (it.id == id) it.copy(songIds = it.songIds + song.id) else it } }
        return true
    }

    fun removeFromPlaylist(id: Long, song: Song) {
        editPlaylists { list -> list.map { if (it.id == id) it.copy(songIds = it.songIds - song.id) else it } }
    }

    /** Imports an .m3u file, matching entries to library songs by file name. Returns matched to total. */
    suspend fun importM3u(uri: Uri): Pair<Int, Int> {
        val resolver = getApplication<Application>().contentResolver
        val text = withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
        } ?: return 0 to 0
        val names = parseM3u(text)
        val byName = _songs.value.associateBy { it.fileName.lowercase() }
        val matched = names.mapNotNull { byName[it.lowercase()] }
        val title = withContext(Dispatchers.IO) {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }?.substringBeforeLast('.') ?: "Imported playlist"
        if (matched.isNotEmpty()) createPlaylist(title, matched)
        return matched.size to names.size
    }

    // --- Sleep timer -------------------------------------------------------------------

    /** Off → 15 → 30 → 60 min → end of song → off. The timer itself runs in the playback service. */
    fun cycleSleep() {
        val c = controller ?: return
        sleepOption = (sleepOption + 1) % SLEEP_OPTIONS.size
        val minutes = SLEEP_OPTIONS[sleepOption]
        c.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SLEEP, Bundle.EMPTY),
            Bundle().apply { putInt(PlaybackService.ARG_MINUTES, minutes) },
        )
        val end = when {
            minutes == 0 -> 0L
            minutes < 0 -> -1L
            else -> System.currentTimeMillis() + minutes * 60_000L
        }
        // Same prefs the service writes; writing here too means the UI shows it immediately.
        prefs.edit { putLong(PlaybackService.KEY_SLEEP_END, end) }
        _ui.update { it.copy(sleepEnd = end) }
    }

    private fun currentSleepEnd(): Long {
        val end = prefs.getLong(PlaybackService.KEY_SLEEP_END, 0L)
        if (end == 0L) sleepOption = 0
        return if (end > 0 && end < System.currentTimeMillis()) 0L else end
    }

    /** Plays a mixtape: Side A then Side B, starting at [index]. The cassette label follows the side. */
    fun playTape(sideA: List<Song>, sideB: List<Song>, index: Int) {
        play(sideA + sideB, index)
        setSideB(sideA.size)
        controller?.shuffleModeEnabled = false // a tape plays in order
    }

    // The service reads this too, so the widget's label shows the right side.
    private fun setSideB(index: Int?) {
        sideBStart = index
        prefs.edit { putInt(PlaybackService.KEY_TAPE_SIDE_B, index ?: -1) }
    }

    fun loadStats() {
        viewModelScope.launch { _stats.value = statsStore.load() }
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    /** Off → all → one → off. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        pendingDirection = 1
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        pendingDirection = -1
        controller?.seekToPrevious()
    }

    fun seekTo(fraction: Float) {
        val c = controller ?: return
        if (c.duration > 0) c.seekTo((fraction * c.duration).toLong())
        _ui.update { it.copy(positionMs = c.currentPosition) }
    }

    /** Hold FF/REW: silent fast winding, like a real deck. [direction] is 1 or -1. */
    fun startScan(direction: Int) {
        val c = controller ?: return
        if (scanJob?.isActive == true) return
        resumeAfterScan = c.isPlaying
        c.pause()
        _ui.update { it.copy(scanDirection = direction) }
        scanJob = viewModelScope.launch {
            var pos = c.currentPosition
            while (isActive) {
                val end = (c.duration - 500).coerceAtLeast(0)
                pos = (pos + direction * SCAN_STEP_MS).coerceIn(0, end)
                c.seekTo(pos)
                _ui.update { it.copy(positionMs = pos) }
                if (pos == 0L || pos == end) break // reached the end of the tape
                delay(100)
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _ui.update { it.copy(scanDirection = 0) }
        if (resumeAfterScan) controller?.play()
        resumeAfterScan = false
    }

    private fun publish(p: Player) {
        val meta = p.mediaMetadata
        val id = p.currentMediaItem?.mediaId
        // Embedded cover art arrives with the metadata; decode only when it changes.
        // ponytail: decoded on the main thread; move to a coroutine if covers are huge.
        val dots = _settings.value.dotArt
        val key = "$id:${meta.artworkData?.size}:$dots"
        if (key != artKey) {
            artKey = key
            art = meta.artworkData?.let { bytes ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    ?.let { if (dots) DotArt.render(it, cells = 22, sizePx = 264) else it }
                    ?.asImageBitmap()
            }
        }
        _ui.value = PlayerUiState(
            title = meta.title?.toString(),
            artist = meta.artist?.toString(),
            isPlaying = p.isPlaying,
            currentMediaId = id,
            positionMs = p.currentPosition,
            durationMs = p.duration.coerceAtLeast(0L),
            trackNumber = p.currentMediaItemIndex + 1,
            trackCount = p.mediaItemCount,
            artwork = art,
            scanDirection = _ui.value.scanDirection,
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            swapDirection = swapDirection,
            sleepEnd = currentSleepEnd(),
            side = sideBStart?.let { if (p.currentMediaItemIndex >= it) "B" else "A" } ?: "A",
        )
        if (p.isPlaying) startTicker() else ticker?.cancel()
    }

    // Position isn't pushed by the player, so poll it while playing.
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                controller?.let { c -> _ui.update { it.copy(positionMs = c.currentPosition) } }
                delay(250)
            }
        }
    }

    companion object {
        const val ACTION_SHUFFLE_ALL = "com.blacksand.player.SHUFFLE_ALL"
        const val ACTION_RESUME = "com.blacksand.player.RESUME"
        private const val SCAN_STEP_MS = 2000L // every 100 ms → 20x speed
        private val SLEEP_OPTIONS = listOf(0, 15, 30, 60, -1)
    }

    override fun onCleared() {
        settingsPrefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
    }
}
