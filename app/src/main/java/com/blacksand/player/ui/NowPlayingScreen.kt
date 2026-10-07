package com.blacksand.player.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacksand.player.PlayerUiState
import com.blacksand.player.PlayerViewModel

@Composable
fun NowPlayingScreen(vm: PlayerViewModel, onClose: () -> Unit) {
    val ui by vm.ui.collectAsState()
    BackHandler(onBack = onClose)

    Column(
        Modifier
            .fillMaxSize()
            .background(Sand.Black)
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
            Text("NOW PLAYING", color = Sand.Dim, fontSize = 12.sp, letterSpacing = 2.sp)
        }

        Cassette(ui, Modifier.fillMaxWidth())

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
                .clip(RoundedCornerShape(16.dp))
                .background(Sand.Recess)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TransportKey(KeyIcon.Prev, "Previous track", vm::previous, Modifier.weight(1f))
            TransportKey(
                KeyIcon.PlayPause, if (ui.isPlaying) "Pause" else "Play", vm::togglePlay,
                Modifier.weight(1f), latched = ui.isPlaying,
            )
            TransportKey(KeyIcon.Next, "Next track", vm::next, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Cassette(ui: PlayerUiState, modifier: Modifier) {
    val bodyShape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(bodyShape)
            .background(Sand.Body)
            .border(1.dp, Sand.Line, bodyShape)
            .padding(12.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Sand.Label)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.height(120.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AlbumArt(ui.artwork, Modifier.size(120.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text("SIDE A", color = Sand.InkDim, fontSize = 11.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "%02d/%02d".format(ui.trackNumber, ui.trackCount),
                        fontFamily = DotFont,
                        fontSize = 28.sp,
                        color = Sand.Ink,
                    )
                }
            }
            TapeWindow(ui.isPlaying, ui.progress, Modifier.fillMaxWidth().height(96.dp))
        }
    }
}

@Composable
private fun AlbumArt(art: ImageBitmap?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFCFCCC5))) {
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
                val step = 6.dp.toPx()
                var y = size.height * 0.45f
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) { drawCircle(Sand.Ink, 1.4.dp.toPx(), Offset(x, y)); x += step }
                    y += step
                }
            }
        }
    }
}

/**
 * Two reels. Tape moves from left to right with progress. Like real tape, the fuller
 * reel turns slower. On pause they coast to a stop instead of freezing.
 */
@Composable
private fun TapeWindow(isPlaying: Boolean, progress: Float, modifier: Modifier) {
    val speed by animateFloatAsState(
        if (isPlaying) 1f else 0f,
        tween(if (isPlaying) 400 else 900, easing = LinearOutSlowInEasing),
        label = "reelSpeed",
    )
    val leftFrac = 0.32f + 0.16f * (1f - progress) // reel radius as a fraction of window height
    val rightFrac = 0.32f + 0.16f * progress

    var leftAngle by remember { mutableFloatStateOf(20f) }
    var rightAngle by remember { mutableFloatStateOf(75f) }
    val currentSpeed by rememberUpdatedState(speed)
    val l by rememberUpdatedState(leftFrac)
    val r by rememberUpdatedState(rightFrac)

    val moving = speed > 0.001f
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1e9f
                    leftAngle = (leftAngle + currentSpeed * dt * 55f / l) % 360f
                    rightAngle = (rightAngle + currentSpeed * dt * 55f / r) % 360f
                }
                last = now
            }
        }
    }

    Canvas(modifier.clip(RoundedCornerShape(50)).background(Color(0xFF0E0E0E))) {
        val h = size.height
        val cy = h / 2
        val left = Offset(h * 0.6f, cy)
        val right = Offset(size.width - h * 0.6f, cy)

        drawLine(
            Color(0xFF3A332E),
            Offset(left.x, cy + leftFrac * h), Offset(right.x, cy + rightFrac * h),
            strokeWidth = 2.dp.toPx(),
        )
        drawCircle(Color(0xFF2B2B2B), leftFrac * h, left)
        drawCircle(Color(0xFF2B2B2B), rightFrac * h, right)
        hub(left, leftAngle)
        hub(right, rightAngle)

        val ww = size.width * 0.22f
        drawRoundRect(
            Color(0xFF1A1A1A),
            topLeft = Offset(size.width / 2 - ww / 2, cy - h * 0.17f),
            size = Size(ww, h * 0.34f),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
    }
}

private fun DrawScope.hub(c: Offset, angle: Float) {
    val r = size.height * 0.14f
    val hole = Color(0xFF0E0E0E)
    drawCircle(Sand.White, r, c)
    rotate(angle, pivot = c) {
        repeat(3) { i ->
            rotate(i * 120f, pivot = c) {
                drawRect(hole, Offset(c.x - r * 0.12f, c.y - r * 0.92f), Size(r * 0.24f, r * 0.42f))
            }
        }
    }
    drawCircle(hole, r * 0.32f, c)
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
