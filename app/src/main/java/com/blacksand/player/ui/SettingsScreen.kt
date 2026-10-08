package com.blacksand.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacksand.player.PlayerViewModel

@Composable
fun SettingsScreen(vm: PlayerViewModel, onClose: () -> Unit) {
    val s by vm.settings.collectAsState()
    BackHandler(onBack = onClose)

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Sand.Black)
            .pointerInput(Unit) { detectTapGestures { } } // don't let taps reach the library underneath
            .grain(),
        contentPadding = WindowInsets.systemBars.asPaddingValues(),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SETTINGS", fontFamily = DotFont, fontSize = 32.sp, color = Sand.White)
                Spacer(Modifier.weight(1f))
                Text("CLOSE", Modifier.clickable(onClick = onClose).padding(vertical = 12.dp),
                    color = Sand.Dim, fontSize = 12.sp, letterSpacing = 2.sp)
            }
        }

        // --- Sound ---
        item { Section("SOUND") }
        item {
            SettingSwitch("Equalizer", "Shape the sound with the faders below.", s.eqOn, vm::setEqOn)
        }
        item {
            if (s.eqBands.isEmpty()) {
                Text("Play a song once to load your phone's equalizer.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = Sand.Dim, fontSize = 12.sp)
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).alpha(if (s.eqOn) 1f else 0.4f)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Sand.Recess)
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        s.eqBands.forEachIndexed { i, hz ->
                            val level = s.eqLevels.getOrElse(i) { 0 }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "%+d".format(level / 100), fontFamily = DotFont, fontSize = 14.sp,
                                    color = if (level != 0) Sand.White else Sand.Dim,
                                )
                                Spacer(Modifier.height(6.dp))
                                Fader(
                                    value = (level - s.eqMin).toFloat() / (s.eqMax - s.eqMin),
                                    label = "${formatHz(hz)} band",
                                    enabled = s.eqOn,
                                ) { v -> vm.setEqLevel(i, (s.eqMin + v * (s.eqMax - s.eqMin)).toInt() / 100 * 100) }
                                Spacer(Modifier.height(6.dp))
                                Text(formatHz(hz), fontSize = 10.sp, color = Sand.Dim)
                            }
                        }
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        Pill("FLAT", enabled = s.eqOn, onClick = vm::resetEq)
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bass boost", Modifier.weight(1f), fontFamily = TitleFont, fontWeight = FontWeight.Medium,
                        fontSize = 16.sp, color = Sand.White)
                    Text(if (s.bass == 0) "OFF" else "${s.bass / 10}%", fontFamily = DotFont, fontSize = 16.sp,
                        color = if (s.bass > 0) Sand.White else Sand.Dim)
                }
                Spacer(Modifier.height(6.dp))
                HSlider(s.bass / 1000f, "Bass boost") { vm.setBass((it * 1000).toInt() / 50 * 50) }
            }
        }
        item {
            SettingSwitch(
                "Even out volume",
                "Uses ReplayGain tags so loud and quiet songs play at a similar level. Untagged songs play as they are.",
                s.normalize, vm::setNormalize,
            )
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = vm::cycleFade)
                    .semantics { stateDescription = if (s.fadeSeconds == 0) "Off" else "${s.fadeSeconds} seconds" }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Fade between songs", fontFamily = TitleFont, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Sand.White)
                    Text("The end of a song fades out and the next fades in. Albums stay gapless. Tap to change.",
                        fontSize = 12.sp, color = Sand.Dim)
                }
                Spacer(Modifier.width(12.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Sand.Body).padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayLight(s.fadeSeconds > 0, dot = 6.dp)
                    Text(if (s.fadeSeconds == 0) "OFF" else "${s.fadeSeconds}s", fontSize = 11.sp, letterSpacing = 1.5.sp,
                        color = if (s.fadeSeconds > 0) Sand.White else Sand.Dim)
                }
            }
        }

        // --- Library ---
        item { Section("LIBRARY") }
        item {
            SettingSwitch("Skip short clips", "Hides anything under 30 seconds, like voice notes and ringtones.",
                s.skipShort, vm::setSkipShort)
        }
        item {
            Text("FOLDERS", Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                color = Sand.Dim, fontSize = 11.sp, letterSpacing = 2.sp)
            Text("Turn a folder off to hide its songs everywhere in the app.",
                Modifier.padding(horizontal = 20.dp), color = Sand.Dim, fontSize = 12.sp)
        }
        items(s.folders, key = { it.first }) { (folder, count) ->
            val shown = folder !in s.excluded
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.setFolderHidden(folder, hidden = shown) }
                    .semantics { stateDescription = if (shown) "Shown" else "Hidden" }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayLight(shown, dot = 8.dp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        folder.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" },
                        fontFamily = TitleFont, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                        color = if (shown) Sand.White else Sand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("$folder · $count", fontSize = 11.sp, color = Sand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
        fontFamily = DotFont, fontSize = 20.sp, color = Sand.White)
}

/** A row with a title, explanation and a deck switch (light + ON/OFF). */
@Composable
private fun SettingSwitch(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!on) }
            .semantics { stateDescription = if (on) "On" else "Off" }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = TitleFont, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Sand.White)
            Text(detail, fontSize = 12.sp, color = Sand.Dim)
        }
        Spacer(Modifier.width(12.dp))
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).background(Sand.Body).padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayLight(on, dot = 6.dp)
            Text(if (on) "ON" else "OFF", fontSize = 11.sp, letterSpacing = 1.5.sp, color = if (on) Sand.White else Sand.Dim)
        }
    }
}

/** A mixing-desk fader: a slot with a square cap you drag up and down. [value] is 0..1. */
@Composable
private fun Fader(value: Float, label: String, enabled: Boolean, onChange: (Float) -> Unit) {
    val current by rememberUpdatedState(value)
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = drag ?: value
    Canvas(
        Modifier
            .size(width = 36.dp, height = 150.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { drag = current },
                    onDragEnd = { drag = null },
                    onDragCancel = { drag = null },
                ) { change, dy ->
                    val next = ((drag ?: current) - dy / size.height).coerceIn(0f, 1f)
                    drag = next
                    onChange(next)
                    change.consume()
                }
            }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                if (enabled) setProgress { onChange(it); true }
            }
    ) {
        val capH = 16.dp.toPx()
        val travel = size.height - capH
        val cx = size.width / 2
        // slot and centre (0 dB) mark
        drawLine(Color.Black, Offset(cx, capH / 2), Offset(cx, size.height - capH / 2), 4.dp.toPx(), StrokeCap.Round)
        drawLine(Sand.Line, Offset(cx - 10.dp.toPx(), size.height / 2), Offset(cx + 10.dp.toPx(), size.height / 2), 1.dp.toPx())
        // cap: dark key with a white line across it
        val top = (1f - shown) * travel
        drawRoundRect(Color(0xFF2C2C2C), Offset(2.dp.toPx(), top), Size(size.width - 4.dp.toPx(), capH), CornerRadius(3.dp.toPx()))
        drawRect(Color(0xFF3C3C3C), Offset(2.dp.toPx(), top), Size(size.width - 4.dp.toPx(), 1.dp.toPx()))
        drawLine(Sand.White, Offset(6.dp.toPx(), top + capH / 2), Offset(size.width - 6.dp.toPx(), top + capH / 2), 2.dp.toPx())
    }
}

/** Horizontal tide-line slider. [value] is 0..1. */
@Composable
private fun HSlider(value: Float, label: String, onChange: (Float) -> Unit) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                    change.consume()
                }
            }
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                setProgress { onChange(it); true }
            }
    ) {
        val cy = size.height / 2
        val x = size.width * value
        drawLine(Sand.Line, Offset(0f, cy), Offset(size.width, cy), 2.dp.toPx())
        drawLine(Sand.White.copy(alpha = 0.2f), Offset(0f, cy), Offset(x, cy), 9.dp.toPx(), StrokeCap.Round)
        drawLine(Sand.White, Offset(0f, cy), Offset(x, cy), 3.dp.toPx(), StrokeCap.Round)
        drawCircle(Sand.White, 6.dp.toPx(), Offset(x, cy))
    }
}

private fun formatHz(hz: Int) = if (hz >= 1000) "${hz / 1000}k" else "$hz"
