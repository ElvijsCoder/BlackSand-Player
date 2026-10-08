package com.blacksand.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import com.blacksand.player.ui.*

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DeckSounds.init(this)
        val reduce = reduceMotion(this)
        setContent {
            CompositionLocalProvider(LocalReduceMotion provides reduce) {
                BlackSandTheme { AppRoot(viewModel) }
            }
        }
        if (savedInstanceState == null) viewModel.handleShortcut(intent?.action)
    }

    // A home screen shortcut tapped while the app is already open.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.handleShortcut(intent.action)
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
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    // A short "tape loading" moment on launch (skipped when animations are off).
    var loading by rememberSaveable { mutableStateOf(!reduce) }
    LaunchedEffect(Unit) { if (loading) { delay(1100); loading = false } }
    val enter = if (reduce) fadeIn() else slideInVertically { it } + fadeIn()
    val exit = if (reduce) fadeOut() else slideOutVertically { it } + fadeOut()
    LaunchedEffect(vm) { vm.openPlayer.collect { showSettings = false; showPlayer = true } }

    Box(Modifier.fillMaxSize().background(Sand.Black)) {
        if (granted) {
            LibraryScreen(vm, onOpenPlayer = { showPlayer = true }, onOpenSettings = { showSettings = true })
        } else {
            PermissionScreen {
                launcher.launch(arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
        }
        AnimatedVisibility(visible = showSettings, enter = enter, exit = exit) {
            SettingsScreen(vm, onClose = { showSettings = false })
        }
        // The cassette slides up over the library, like loading a tape.
        AnimatedVisibility(visible = showPlayer, enter = enter, exit = exit) {
            NowPlayingScreen(vm, onClose = { showPlayer = false })
        }
        AnimatedVisibility(visible = loading, enter = fadeIn(), exit = fadeOut(tween(350))) {
            TapeLoading()
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
