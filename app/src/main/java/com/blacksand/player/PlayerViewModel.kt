package com.blacksand.player

import android.app.Application
import android.content.ComponentName
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.blacksand.player.data.MusicRepository
import com.blacksand.player.data.Song
import com.blacksand.player.data.toMediaItem
import com.blacksand.player.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = MusicRepository(app)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs = _songs.asStateFlow()

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui = _ui.asStateFlow()

    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(
        app, SessionToken(app, ComponentName(app, PlaybackService::class.java))
    ).buildAsync()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    private var ticker: Job? = null
    private var artKey: String? = null
    private var art: ImageBitmap? = null

    init {
        controllerFuture.addListener({
            controller = controllerFuture.get().also {
                it.addListener(listener)
                publish(it)
            }
        }, ContextCompat.getMainExecutor(app))
    }

    fun loadLibrary() {
        viewModelScope.launch { _songs.value = repository.loadSongs() }
    }

    /** Plays the whole library as the queue, starting at [index]. */
    fun playFrom(index: Int) {
        val c = controller ?: return
        c.setMediaItems(_songs.value.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }

    fun seekTo(fraction: Float) {
        val c = controller ?: return
        if (c.duration > 0) c.seekTo((fraction * c.duration).toLong())
        _ui.update { it.copy(positionMs = c.currentPosition) }
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

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
    }
}
