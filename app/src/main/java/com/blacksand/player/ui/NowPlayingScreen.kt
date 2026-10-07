package com.blacksand.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.media3.common.Player
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacksand.player.PlayerUiState
import com.blacksand.player.PlayerViewModel

@Composable
fun NowPlayingScreen(vm: PlayerViewModel, onClose: () -> Unit) {
    var showQueue by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        NowPlayingDeck(vm, onClose, onQueue = { showQueue = true })
        AnimatedVisibility(
            visible = showQueue,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            QueueSheet(vm, onClose = { showQueue = false })
        }
    }
}

@Composable
private fun NowPlayingDeck(vm: PlayerViewModel, onClose: () -> Unit, onQueue: () -> Unit) {
    val ui by vm.ui.collectAsState()
    BackHandler(onBack = onClose)

    Column(
        Modifier
            .fillMaxSize()
            .background(Sand.Black)
            // Swallow taps on empty space so they don't fall through to the library underneath.
            .pointerInput(Unit) { detectTapGestures { } }
            .grain()
            .systemBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CLOSE",
                Modifier
                    .clickable(onClick = onClose)
                    .padding(vertical = 12.dp),
                color = Sand.Dim,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "QUEUE",
                Modifier
                    .clickable(onClick = onQueue)
                    .padding(vertical = 12.dp),
                color = Sand.Dim,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
        }

        // A new song swaps in a new cassette: forward slides left, back slides right.
        AnimatedContent(
            targetState = ui,
            contentKey = { it.currentMediaId },
            transitionSpec = {
                val dir = targetState.swapDirection
                (slideInHorizontally(tween(350)) { it * dir } + fadeIn(tween(350)))
                    .togetherWith(slideOutHorizontally(tween(350)) { -it * dir } + fadeOut(tween(250)))
                    .using(SizeTransform(clip = false))
            },
            label = "cassetteSwap",
        ) { state ->
            Cassette(state, Modifier.fillMaxWidth())
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                ui.title ?: "",
                fontFamily = TitleFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 26.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Sand.White,
            )
            Text(ui.artist ?: "", color = Sand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckToggle("SHUFFLE", ui.shuffle, vm::toggleShuffle)
            DeckToggle(
                when (ui.repeatMode) {
                    Player.REPEAT_MODE_ALL -> "REPEAT ALL"
                    Player.REPEAT_MODE_ONE -> "REPEAT ONE"
                    else -> "REPEAT"
                },
                ui.repeatMode != Player.REPEAT_MODE_OFF,
                vm::cycleRepeat,
            )
            DeckToggle(
                when {
                    ui.sleepEnd == -1L -> "SLEEP END"
                    ui.sleepEnd > 0L -> "SLEEP ${((ui.sleepEnd - System.currentTimeMillis()) / 60_000 + 1).coerceAtLeast(1)}m"
                    else -> "SLEEP"
                },
                ui.sleepEnd != 0L,
                vm::cycleSleep,
            )
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PlayLight(ui.isPlaying)
            Spacer(Modifier.width(8.dp))
            Text(
                if (ui.isPlaying) "PLAY" else "PAUSE",
                color = Sand.Dim,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(formatTime(ui.positionMs), fontFamily = DotFont, fontSize = 40.sp, color = Sand.White)
            Text(" / ${formatTime(ui.durationMs)}", color = Sand.Dim, fontSize = 13.sp)
        }

        TideSeekBar(ui.progress, onSeek = vm::seekTo)

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Sand.Recess)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TransportKey(
                KeyIcon.Prev, "Previous track, hold to rewind", vm::previous, Modifier.weight(1f), caption = "REW",
                onHoldStart = { vm.startScan(-1) }, onHoldEnd = vm::stopScan,
            )
            TransportKey(
                KeyIcon.PlayPause, if (ui.isPlaying) "Pause" else "Play", vm::togglePlay,
                Modifier.weight(1f), latched = ui.isPlaying, caption = "PLAY", led = true,
            )
            TransportKey(
                KeyIcon.Next, "Next track, hold to fast-forward", vm::next, Modifier.weight(1f), caption = "FF",
                onHoldStart = { vm.startScan(1) }, onHoldEnd = vm::stopScan,
            )
        }
    }
}

/** Upcoming songs in play order. Tap to jump; reorder with ▲▼ (when not shuffled); ✕ removes. */
@Composable
private fun QueueSheet(vm: PlayerViewModel, onClose: () -> Unit) {
    val queue by vm.queue.collectAsState()
    val ui by vm.ui.collectAsState()
    val current = ui.trackNumber - 1
    BackHandler(onBack = onClose)

    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val pos = queue.indexOfFirst { it.index == current }
        if (pos > 0) listState.scrollToItem(pos)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Sand.Black)
            .pointerInput(Unit) { detectTapGestures { } }
            .grain()
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("QUEUE", fontFamily = DotFont, fontSize = 32.sp, color = Sand.White)
            Spacer(Modifier.width(12.dp))
            Text("${queue.size} TRACKS", color = Sand.Dim, fontSize = 12.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "CLOSE",
                Modifier.clickable(onClick = onClose).padding(vertical = 12.dp),
                color = Sand.Dim, fontSize = 12.sp, letterSpacing = 2.sp,
            )
        }
        if (ui.shuffle) {
            Text("Shuffle is on, so this is the shuffled play order. Turn shuffle off to reorder.",
                Modifier.padding(vertical = 6.dp), color = Sand.Dim, fontSize = 12.sp)
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(queue, key = { _, e -> e.index }) { pos, entry ->
                val isCurrent = entry.index == current
                Row(
                    Modifier.fillMaxWidth().clickable { vm.jumpTo(entry.index) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "%02d".format(pos + 1), Modifier.width(40.dp),
                        fontFamily = DotFont, fontSize = 18.sp, color = if (isCurrent) Sand.Red else Sand.Dim,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, fontFamily = TitleFont, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                            color = if (isCurrent) Sand.Red else Sand.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(entry.artist, fontSize = 11.sp, color = Sand.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!ui.shuffle) {
                        QueueButton("▲", "Move up", enabled = entry.index > 0) { vm.moveInQueue(entry.index, entry.index - 1) }
                        QueueButton("▼", "Move down", enabled = entry.index < queue.size - 1) {
                            vm.moveInQueue(entry.index, entry.index + 1)
                        }
                    }
                    QueueButton("✕", "Remove from queue", enabled = !isCurrent) { vm.removeFromQueue(entry.index) }
                }
            }
        }
    }
}

@Composable
private fun QueueButton(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (enabled) Sand.Dim else Sand.Line, fontSize = 13.sp)
    }
}

/** A small deck switch: label plus a light that's red while on. */
@Composable
private fun DeckToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Sand.Body)
            .clickable(onClick = onClick)
            .semantics { stateDescription = if (on) "On" else "Off" }
            .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayLight(on, dot = 6.dp)
        Text(label, fontSize = 11.sp, letterSpacing = 1.5.sp, color = if (on) Sand.White else Sand.Dim)
    }
}

private val LabelLine = Color(0xFFB9B6AE)
private val LabelEdge = Color(0xFFCFCCC5)
private val Hole = Color(0xFF0E0E0E)

/**
 * Option A: black matte shell with screws, a hand-ruled label, the tape window cut through
 * it, and the guide-hole section at the bottom. Laid out in the mockup's 392x250 units,
 * scaled to the screen width.
 */
@Composable
private fun Cassette(ui: PlayerUiState, modifier: Modifier) {
    BoxWithConstraints(modifier.aspectRatio(392f / 250f)) {
        val u = maxWidth / 392f
        val k = u.value // text scales with the cassette

        Canvas(Modifier.fillMaxSize()) { drawShell() }

        Box(
            Modifier
                .offset(u * 26, u * 18)
                .size(u * 340, u * 154)
                .clip(RoundedCornerShape(u * 6))
                .background(Sand.Label)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = u * 12, end = u * 12, top = u * 8),
                horizontalArrangement = Arrangement.spacedBy(u * 10),
            ) {
                Text(
                    "A", fontFamily = TitleFont, fontWeight = FontWeight.Bold,
                    fontSize = (30 * k).sp, lineHeight = (30 * k).sp, color = Sand.Ink,
                )
                Column(Modifier.weight(1f)) {
                    RuledLine(ui.title ?: "", TitleFont, FontWeight.SemiBold, (14 * k).sp, Sand.Ink, u)
                    RuledLine(ui.artist ?: "", MonoFont, FontWeight.Normal, (11 * k).sp, Sand.InkDim, u)
                }
                AlbumArt(ui.artwork, Modifier.size(u * 42))
            }

            TapeWindow(ui.isPlaying, ui.scanDirection, ui.progress, Modifier.offset(u * 46, u * 62).size(u * 248, u * 70))

            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(u * 16)
                    .background(Color(0xFF151515))
                    .padding(horizontal = u * 12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TYPE I · NORMAL", color = LabelEdge, fontSize = (8 * k).sp, letterSpacing = (1.8f * k).sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "%02d/%02d".format(ui.trackNumber, ui.trackCount),
                    fontFamily = DotFont, color = LabelEdge, fontSize = (10 * k).sp,
                )
            }
        }
    }
}

/** A line of "handwriting" on the label, sitting on a ruled line. */
@Composable
private fun RuledLine(text: String, family: FontFamily, weight: FontWeight, textSize: TextUnit, color: Color, u: Dp) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(LabelLine, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(vertical = u * 2),
        fontFamily = family,
        fontWeight = weight,
        fontSize = textSize,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Shell, screws and the bottom guide-hole section, in 392x250 mockup units. */
private fun DrawScope.drawShell() {
    val u = size.width / 392f
    val corner = CornerRadius(u * 14)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF1C1C1C), Color(0xFF121212))), cornerRadius = corner)
    drawRoundRect(Color(0xFF2A2A2A), cornerRadius = corner, style = Stroke(1.dp.toPx()))

    // Bottom section: a trapezoid with two guide holes, three small holes and visible tape.
    val top = u * 194
    val bottom = size.height
    drawPath(
        Path().apply {
            moveTo(u * 89.4f, top); lineTo(u * 302.6f, top)
            lineTo(u * 326, bottom); lineTo(u * 66, bottom); close()
        },
        Color(0xFF0F0F0F),
    )
    for (x in listOf(117.7f, 274.3f)) {
        drawCircle(Color(0xFF050505), u * 7.5f, Offset(x * u, u * 215.5f))
        drawCircle(Color(0xFF262626), u * 8.5f, Offset(x * u, u * 215.5f), style = Stroke(u * 2))
    }
    for ((x, w) in listOf(167f to 8f, 189f to 14f, 217f to 8f)) {
        drawRect(Color(0xFF050505), Offset(x * u, u * 212), Size(w * u, u * 8))
    }
    drawRect(Color(0xFF3A332E), Offset(u * 86.8f, u * 238), Size(u * 218.4f, u * 5))

    // Five screws, each slot at its own angle.
    listOf(
        Triple(14.5f, 14.5f, 35f), Triple(377.5f, 14.5f, -20f), Triple(14.5f, 235.5f, 70f),
        Triple(377.5f, 235.5f, 10f), Triple(196f, 235.5f, -45f),
    ).forEach { (x, y, a) ->
        val c = Offset(x * u, y * u)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF4A4A4A), Color(0xFF161616)), center = c - Offset(u * 2, u * 2), radius = u * 6),
            u * 5.5f, c,
        )
        rotate(a, c) { drawRect(Color(0xFF0A0A0A), Offset(c.x - u * 3.5f, c.y - u * 0.75f), Size(u * 7, u * 1.5f)) }
    }
}

@Composable
private fun AlbumArt(art: ImageBitmap?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(LabelEdge)) {
        if (art != null) {
            // ponytail: greyscale for now; true dot-matrix dithering is a later feature.
            Image(
                art, contentDescription = "Album art",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
            )
        } else {
            Canvas(Modifier.fillMaxSize()) { // no cover: a dot-screen "horizon"
                val step = 4.dp.toPx()
                var y = size.height * 0.45f
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) { drawCircle(Sand.Ink, 1.dp.toPx(), Offset(x, y)); x += step }
                    y += step
                }
            }
        }
    }
}

/**
 * The tape window: two reels, tape moving left to right with progress. Like real tape, the
 * fuller reel turns slower. On pause they coast to a stop instead of freezing.
 */
@Composable
private fun TapeWindow(isPlaying: Boolean, scan: Int, progress: Float, modifier: Modifier) {
    // Signed speed: 1 = playing, ±6 = winding (negative turns the reels backwards), 0 = stopped.
    val target = when {
        scan != 0 -> 6f * scan
        isPlaying -> 1f
        else -> 0f
    }
    val speed by animateFloatAsState(
        target,
        tween(if (target == 0f) 900 else 400, easing = LinearOutSlowInEasing),
        label = "reelSpeed",
    )
    val leftFrac = 0.204f + 0.204f * (1f - progress) // tape pack radius, as a fraction of window height
    val rightFrac = 0.204f + 0.204f * progress

    var leftAngle by remember { mutableFloatStateOf(20f) }
    var rightAngle by remember { mutableFloatStateOf(75f) }
    val currentSpeed by rememberUpdatedState(speed)
    val l by rememberUpdatedState(leftFrac)
    val r by rememberUpdatedState(rightFrac)

    val moving = kotlin.math.abs(speed) > 0.001f
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1e9f
                    leftAngle = (leftAngle + currentSpeed * dt * 40f / l) % 360f
                    rightAngle = (rightAngle + currentSpeed * dt * 40f / r) % 360f
                }
                last = now
            }
        }
    }

    Canvas(modifier) {
        val h = size.height
        val pill = CornerRadius(h / 2)
        val edge = h * 3f / 70f
        drawRoundRect(LabelEdge, cornerRadius = pill) // the label's edge around the cut-out
        drawRoundRect(
            Hole, topLeft = Offset(edge, edge), size = Size(size.width - 2 * edge, h - 2 * edge),
            cornerRadius = CornerRadius(h / 2 - edge),
        )

        val cy = h / 2
        val left = Offset(h * 0.53f, cy)
        val right = Offset(size.width - h * 0.53f, cy)

        // Small centre window with the tape running along its bottom.
        val ww = h
        val wh = h * 0.4f
        val wx = size.width / 2 - ww / 2
        drawRect(Color(0xFF161616), Offset(wx, cy - wh / 2), Size(ww, wh))
        var sx = wx + h * 0.086f
        while (sx < wx + ww) { drawRect(Color(0xFF1B1B1B), Offset(sx, cy - wh / 2), Size(h * 0.014f, wh)); sx += h * 0.1f }
        drawRect(Color(0xFF3A332E), Offset(wx, cy + wh / 2 - h * 0.071f), Size(ww, h * 0.071f))
        drawRect(Color(0xFF2C2C2C), Offset(wx, cy - wh / 2), Size(ww, wh), style = Stroke(1.dp.toPx()))

        for ((c, frac, angle) in listOf(Triple(left, leftFrac, leftAngle), Triple(right, rightFrac, rightAngle))) {
            drawCircle(Color(0xFF2A2420), frac * h, c)
            drawCircle(Color(0xFF3A312B), frac * h - h * 0.051f, c, style = Stroke(1.dp.toPx()))
            hub(c, angle, h)
        }
    }
}

/** Reel hub: white ring, drive teeth pointing into the hole, three spoke holes. */
private fun DrawScope.hub(c: Offset, angle: Float, h: Float) {
    drawCircle(Sand.White, 0.173f * h, c)
    drawCircle(Hole, 0.092f * h, c)
    repeat(6) { i ->
        rotate(angle + i * 60f, c) {
            drawRect(Sand.White, Offset(c.x - 0.0155f * h, c.y - 0.092f * h), Size(0.031f * h, 0.041f * h))
        }
    }
    repeat(3) { i ->
        rotate(angle + 30f + i * 120f, c) { drawCircle(Hole, 0.0245f * h, Offset(c.x, c.y - 0.128f * h)) }
    }
}

/** Progress as a tide line: white foam creeping along the black. Tap or drag to seek. */
@Composable
private fun TideSeekBar(progress: Float, onSeek: (Float) -> Unit) {
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val shown = dragFrac ?: progress
    val wobble by rememberInfiniteTransition(label = "foam").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse), label = "wobble",
    )

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragFrac = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragFrac?.let(onSeek); dragFrac = null },
                    onDragCancel = { dragFrac = null },
                ) { change, _ -> dragFrac = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .semantics {
                contentDescription = "Seek"
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                setProgress { onSeek(it); true }
            }
    ) {
        val cy = size.height / 2
        val x = size.width * shown
        drawLine(Sand.Line, Offset(0f, cy), Offset(size.width, cy), 2.dp.toPx())
        drawLine(Sand.White.copy(alpha = 0.2f), Offset(0f, cy), Offset(x, cy), 9.dp.toPx(), StrokeCap.Round)
        drawLine(Sand.White, Offset(0f, cy), Offset(x, cy), 3.dp.toPx(), StrokeCap.Round)
        val d = 3.dp.toPx()
        drawCircle(Sand.White.copy(alpha = 0.8f), 2.dp.toPx(), Offset(x + d * (1 + wobble), cy - d * wobble))
        drawCircle(Sand.White.copy(alpha = 0.5f), 1.5.dp.toPx(), Offset(x + d * (2.5f - wobble), cy + d * (0.6f + 0.4f * wobble)))
        drawCircle(Sand.White.copy(alpha = 0.3f), 1.dp.toPx(), Offset(x + d * (3.5f + wobble), cy - d * 0.3f))
    }
}
