package com.blacksand.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
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
import com.blacksand.player.data.MusicRepository
import com.blacksand.player.data.Playlist
import com.blacksand.player.data.PlaylistStore
import com.blacksand.player.data.parseM3u
import com.blacksand.player.data.Song
import com.blacksand.player.data.toMediaItem
import com.blacksand.player.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

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
            }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch { _playlists.value = store.load() }
    }

    fun loadLibrary() {
        viewModelScope.launch { _songs.value = repository.loadSongs() }
    }

    /** Plays [list] as the queue, starting at [index]. */
    fun play(list: List<Song>, index: Int) {
        val c = controller ?: return
        pendingDirection = 1
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

    fun addToPlaylist(id: Long, song: Song) {
        editPlaylists { list ->
            list.map { if (it.id == id && song.id !in it.songIds) it.copy(songIds = it.songIds + song.id) else it }
        }
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
        val key = "$id:${meta.artworkData?.size}"
        if (key != artKey) {
            artKey = key
            art = meta.artworkData?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
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

    private companion object {
        const val SCAN_STEP_MS = 2000L // every 100 ms → 20x speed
        val SLEEP_OPTIONS = listOf(0, 15, 30, 60, -1)
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
    }
}
