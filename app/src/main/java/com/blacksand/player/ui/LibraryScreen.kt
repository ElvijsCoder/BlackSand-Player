package com.blacksand.player.ui

import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacksand.player.PlayerUiState
import com.blacksand.player.PlayerViewModel
import com.blacksand.player.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Tab(val title: String) { Songs("SONGS"), Albums("ALBUMS"), Artists("ARTISTS"), Folders("FOLDERS") }

/** An album, artist or folder: a titled list of songs. */
private data class Group(val key: String, val title: String, val subtitle: String, val songs: List<Song>)

private fun albumsOf(songs: List<Song>) = songs.groupBy { it.albumId }.map { (id, list) ->
    val artist = list.groupingBy { it.artist }.eachCount().maxBy { it.value }.key
    Group("a:$id", list.first().album.ifBlank { "Unknown album" }, "$artist · ${list.size} TRACKS",
        list.sortedWith(compareBy({ it.track }, { it.title })))
}.sortedBy { it.title.lowercase() }

private fun artistsOf(songs: List<Song>) = songs.groupBy { it.artist }.map { (artist, list) ->
    val albums = list.map { it.albumId }.distinct().size
    Group("r:$artist", artist, "${list.size} TRACKS · $albums ALBUMS",
        list.sortedWith(compareBy({ it.album.lowercase() }, { it.track })))
}.sortedBy { it.title.lowercase() }

private fun foldersOf(songs: List<Song>) = songs.groupBy { it.folder }.map { (folder, list) ->
    val name = folder.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" }
    Group("f:$folder", name, "$folder · ${list.size} TRACKS", list.sortedBy { it.title.lowercase() })
}.sortedBy { it.subtitle.lowercase() }

@Composable
fun LibraryScreen(vm: PlayerViewModel, onOpenPlayer: () -> Unit) {
    val songs by vm.songs.collectAsState()
    val ui by vm.ui.collectAsState()

    var tab by rememberSaveable { mutableStateOf(Tab.Songs) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }

    val albums = remember(songs) { albumsOf(songs) }
    val artists = remember(songs) { artistsOf(songs) }
    val folders = remember(songs) { foldersOf(songs) }
    val open = remember(openKey, albums, artists, folders) {
        openKey?.let { key -> (albums + artists + folders).firstOrNull { it.key == key } }
    }
    val results = remember(query, songs) {
        val q = query.trim()
        if (q.isEmpty()) emptyList()
        else songs.filter { s -> listOf(s.title, s.artist, s.album).any { it.contains(q, ignoreCase = true) } }
    }

    BackHandler(enabled = open != null || searching) {
        if (open != null) openKey = null else { searching = false; query = "" }
    }

    fun play(list: List<Song>, index: Int) {
        vm.play(list, index)
        onOpenPlayer()
    }

    Column(Modifier.fillMaxSize().grain()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp).padding(top = 16.dp)) {
            if (open != null) {
                Text(
                    "‹ BACK",
                    Modifier.clickable { openKey = null }.padding(vertical = 8.dp),
                    color = Sand.Dim, fontSize = 12.sp, letterSpacing = 2.sp,
                )
                Text(open.title, fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 26.sp,
                    color = Sand.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(open.subtitle, Modifier.weight(1f), color = Sand.Dim, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Pill("PLAY") { play(open.songs, 0) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("LIBRARY", fontFamily = DotFont, fontSize = 32.sp, color = Sand.White)
                    Spacer(Modifier.weight(1f))
                    Pill(if (searching) "CLOSE" else "SEARCH") {
                        searching = !searching
                        if (!searching) query = ""
                    }
                }
                if (searching) {
                    SearchField(query, { query = it })
                } else {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Tab.entries.forEach { t ->
                            TabLabel(t.title, t == tab) { tab = t }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Box(Modifier.weight(1f)) {
            when {
                open != null -> SongList(open.songs, ui) { play(open.songs, it) }
                searching -> if (query.isBlank()) {
                    Hint("Search songs, artists and albums.")
                } else if (results.isEmpty()) {
                    Hint("Nothing matches \"${query.trim()}\".")
                } else {
                    SongList(results, ui) { play(results, it) }
                }
                songs.isEmpty() -> Hint("No music found on this phone yet.")
                tab == Tab.Songs -> SongList(songs, ui) { play(songs, it) }
                else -> GroupList(
                    when (tab) { Tab.Albums -> albums; Tab.Artists -> artists; else -> folders },
                ) { openKey = it.key }
            }
        }

        if (ui.currentMediaId != null) MiniPlayer(ui, vm, onOpenPlayer)
        else Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun TabLabel(title: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 12.sp, letterSpacing = 1.5.sp, color = if (selected) Sand.White else Sand.Dim)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.size(4.dp).clip(RoundedCornerShape(50)).background(if (selected) Sand.Red else Sand.Black))
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Sand.Key)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = Sand.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
    )
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Sand.Body)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) Text("Title, artist or album", color = Sand.Dim, fontSize = 15.sp)
        BasicTextField(
            value, onChange,
            Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            textStyle = TextStyle(fontFamily = MonoFont, fontSize = 15.sp, color = Sand.White),
            cursorBrush = SolidColor(Sand.Red),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(20.dp), color = Sand.Dim)
}

@Composable
private fun SongList(songs: List<Song>, ui: PlayerUiState, onPlay: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
            SongRow(song, isCurrent = song.id.toString() == ui.currentMediaId) { onPlay(index) }
        }
    }
}

@Composable
private fun SongRow(song: Song, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                song.title, fontFamily = TitleFont, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) Sand.Red else Sand.White,
            )
            Text(song.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Sand.Dim)
        }
        Text(formatTime(song.durationMs), fontSize = 12.sp, color = Sand.Dim)
    }
}

@Composable
private fun GroupList(groups: List<Group>, onOpen: (Group) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(groups, key = { it.key }) { group ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(group) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverThumb(group.songs.first(), Modifier.size(48.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.title, fontFamily = TitleFont, fontWeight = FontWeight.Medium, fontSize = 16.sp,
                        color = Sand.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(group.subtitle, fontSize = 11.sp, color = Sand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// Small cover thumbnails, cached so scrolling doesn't reload them.
private val thumbCache = LruCache<Long, ImageBitmap>(200)

@Composable
private fun CoverThumb(song: Song, modifier: Modifier) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(thumbCache.get(song.id), song.id) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(song.uri, Size(144, 144), null).asImageBitmap()
                }.getOrNull()
            }?.also { thumbCache.put(song.id, it) }
        }
    }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Sand.Body)) {
        val art = thumb
        if (art != null) {
            Image(
                art, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
            )
        } else {
            Canvas(Modifier.fillMaxSize()) { // no cover: a dot-screen "horizon"
                val step = 4.dp.toPx()
                var y = size.height * 0.5f
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) { drawCircle(Sand.Dim, 0.8.dp.toPx(), Offset(x, y)); x += step }
                    y += step
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(ui: PlayerUiState, vm: PlayerViewModel, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Sand.Body).navigationBarsPadding()) {
        // Thin tide line along the top edge.
        Box(Modifier.fillMaxWidth().height(2.dp).background(Sand.Line)) {
            Box(Modifier.fillMaxWidth(ui.progress).fillMaxHeight().background(Sand.White))
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayLight(ui.isPlaying, dot = 8.dp)
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(ui.title ?: "", fontFamily = TitleFont, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = Sand.White)
                    Text(ui.artist ?: "", fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = Sand.Dim)
                }
            }
            TransportKey(KeyIcon.Prev, "Previous track", vm::previous, Modifier.width(52.dp), height = 44.dp)
            TransportKey(
                KeyIcon.PlayPause, if (ui.isPlaying) "Pause" else "Play", vm::togglePlay,
                Modifier.width(52.dp), latched = ui.isPlaying, height = 44.dp,
            )
            TransportKey(KeyIcon.Next, "Next track", vm::next, Modifier.width(52.dp), height = 44.dp)
        }
    }
}
