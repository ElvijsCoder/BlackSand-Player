package com.blacksand.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blacksand.player.data.Song
import com.blacksand.player.ui.*

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BlackSandTheme { AppRoot(viewModel) } }
    }
}

@Composable
private fun AppRoot(vm: PlayerViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> granted = result[Manifest.permission.READ_MEDIA_AUDIO] == true }

    LaunchedEffect(granted) { if (granted) vm.loadLibrary() }

    var showPlayer by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Sand.Black)) {
        if (granted) {
            LibraryScreen(vm, onOpenPlayer = { showPlayer = true })
        } else {
            PermissionScreen {
                launcher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        // The cassette slides up over the library, like loading a tape.
        AnimatedVisibility(
            visible = showPlayer,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            NowPlayingScreen(vm, onClose = { showPlayer = false })
        }
    }
}

@Composable
private fun PermissionScreen(onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxSize().grain().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("BLACK SAND", fontFamily = DotFont, fontSize = 36.sp, color = Sand.White)
        Spacer(Modifier.height(12.dp))
        Text("Needs access to your music.", color = Sand.Dim)
        Spacer(Modifier.height(24.dp))
        Text(
            "ALLOW",
            Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(Sand.Key)
                .clickable(onClick = onAllow)
                .padding(horizontal = 28.dp, vertical = 14.dp),
            color = Sand.White,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
    }
}

@Composable
private fun LibraryScreen(vm: PlayerViewModel, onOpenPlayer: () -> Unit) {
    val songs by vm.songs.collectAsState()
    val ui by vm.ui.collectAsState()

    Column(Modifier.fillMaxSize().grain()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("LIBRARY", fontFamily = DotFont, fontSize = 32.sp, color = Sand.White)
            Spacer(Modifier.weight(1f))
            Text("${songs.size} TRACKS", color = Sand.Dim, fontSize = 12.sp, letterSpacing = 2.sp)
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(songs, key = { _, s -> s.id }) { index, song ->
                SongRow(song, isCurrent = song.id.toString() == ui.currentMediaId) {
                    vm.playFrom(index)
                    onOpenPlayer()
                }
            }
        }
        if (ui.currentMediaId != null) MiniPlayer(ui, vm, onOpenPlayer)
        else Spacer(Modifier.navigationBarsPadding())
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
                song.title,
                fontFamily = TitleFont,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) Sand.Red else Sand.White,
            )
            Text(song.artist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Sand.Dim)
        }
        Text(formatTime(song.durationMs), fontSize = 12.sp, color = Sand.Dim)
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
