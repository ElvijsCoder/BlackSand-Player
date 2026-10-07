package com.blacksand.player.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.blacksand.player.PlayerViewModel
import com.blacksand.player.data.Playlist
import com.blacksand.player.data.Song

/** Small rounded button used across the library. */
@Composable
fun Pill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Sand.Key)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        color = Sand.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
    )
}

@Composable
private fun SandDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Sand.Body)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        color = Sand.White, letterSpacing = 1.5.sp, fontSize = 13.sp,
    )
}

/** The ⋯ menu for a song. [playlistId] is set when opened from inside that playlist. */
@Composable
fun SongMenu(
    song: Song,
    playlists: List<Playlist>,
    playlistId: Long?,
    vm: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    if (naming) {
        NameDialog("New playlist", "", onDismiss = { naming = false }) { name ->
            vm.createPlaylist(name, listOf(song))
            toast("Added to $name")
            onDismiss()
        }
        return
    }

    SandDialog(onDismiss) {
        Text(song.title, fontFamily = TitleFont, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
            color = Sand.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(song.artist, fontSize = 12.sp, color = Sand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        if (!choosing) {
            MenuItem("PLAY NEXT") { vm.playNext(song); toast("Plays next"); onDismiss() }
            MenuItem("ADD TO QUEUE") { vm.addToQueue(song); toast("Added to queue"); onDismiss() }
            MenuItem("ADD TO PLAYLIST") { choosing = true }
            if (playlistId != null) {
                MenuItem("REMOVE FROM PLAYLIST") { vm.removeFromPlaylist(playlistId, song); onDismiss() }
            }
        } else {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                playlists.forEach { p ->
                    MenuItem(p.name) { vm.addToPlaylist(p.id, song); toast("Added to ${p.name}"); onDismiss() }
                }
            }
            MenuItem("+ NEW PLAYLIST") { naming = true }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SandDialog(onDismiss) {
        Text(title.uppercase(), color = Sand.Dim, fontSize = 12.sp, letterSpacing = 2.sp)
        Box(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(10.dp))
                .background(Sand.Black).padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (name.isEmpty()) Text("Name", color = Sand.Dim, fontSize = 15.sp)
            BasicTextField(
                name, { name = it },
                Modifier.fillMaxWidth().focusRequester(focus),
                singleLine = true,
                textStyle = TextStyle(fontFamily = MonoFont, fontSize = 15.sp, color = Sand.White),
                cursorBrush = SolidColor(Sand.Red),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            Pill("CANCEL", onClick = onDismiss)
            Pill("SAVE", enabled = name.isNotBlank()) { onSave(name.trim()) }
        }
    }
}

@Composable
fun ConfirmDialog(text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    SandDialog(onDismiss) {
        Text(text, color = Sand.White, fontSize = 14.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            Pill("CANCEL", onClick = onDismiss)
            Pill(confirm, onClick = onConfirm)
        }
    }
}
