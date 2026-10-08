package com.blacksand.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class KeyIcon { Prev, PlayPause, Next }

private const val HOLD_MS = 450L

/**
 * A tall piano key from an old tape deck: sinks in while pressed, clicks on press and release.
 * [latched] keeps it down (the play key while music plays). [caption] is the engraved label,
 * [led] adds a red light next to it that's lit while latched.
 */
@Composable
fun TransportKey(
    icon: KeyIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    latched: Boolean = false,
    height: Dp = 72.dp,
    caption: String? = null,
    led: Boolean = false,
    onHoldStart: (() -> Unit)? = null,
    onHoldEnd: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    val click by rememberUpdatedState(onClick)
    val holdStart by rememberUpdatedState(onHoldStart)
    val holdEnd by rememberUpdatedState(onHoldEnd)
    val pressed by interaction.collectIsPressedAsState()
    val down = pressed || latched
    // Key geometry: a top face plus a front edge (the key's thickness). Pressing sinks the key
    // into the housing, so the front edge mostly disappears; on release it springs back up.
    val edge = height * 0.17f
    val travel by animateDpAsState(
        if (down) edge * 0.7f else 0.dp,
        if (down) tween<Dp>(70) else spring<Dp>(dampingRatio = 0.55f, stiffness = 1400f),
        label = "keyTravel",
    )

    val view = LocalView.current
    var wasPressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed != wasPressed) {
            view.performHapticFeedback(
                if (pressed) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.KEYBOARD_RELEASE
            )
            if (pressed) { if (icon == KeyIcon.PlayPause) DeckSounds.latch() else DeckSounds.click() }
            wasPressed = pressed
        }
    }

    val topShape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp)
    val frontShape = RoundedCornerShape(bottomStart = 7.dp, bottomEnd = 7.dp)
    val topFace = if (down) {
        Brush.verticalGradient(listOf(Color(0xFF202020), Color(0xFF181818)))
    } else {
        Brush.verticalGradient(0f to Color(0xFF353535), 0.3f to Color(0xFF262626), 1f to Color(0xFF1C1C1C))
    }
    val frontFace = Brush.verticalGradient(listOf(Color(0xFF151515), Color(0xFF070707)))

    Box(
        modifier
            .height(height)
            .then(
                if (onHoldStart == null) {
                    Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                } else {
                    // Tap = onClick; hold past HOLD_MS = onHoldStart, then onHoldEnd on release.
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(onPress = { offset ->
                                val press = PressInteraction.Press(offset)
                                interaction.emit(press)
                                var held = false
                                val holdJob = scope.launch {
                                    delay(HOLD_MS)
                                    held = true
                                    holdStart?.invoke()
                                }
                                val released = tryAwaitRelease()
                                holdJob.cancel()
                                interaction.emit(
                                    if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press)
                                )
                                if (held) holdEnd?.invoke() else if (released) click()
                            })
                        }
                        .semantics {
                            role = Role.Button
                            this.onClick(label = null, action = { click(); true })
                        }
                }
            )
            .semantics { contentDescription = label },
    ) {
        Column(Modifier.fillMaxWidth().offset(y = travel)) {
            // Top face, with bevels: light on the top and left edges, dark on the right.
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(height - edge)
                    .clip(topShape)
                    .background(topFace)
                    .drawBehind {
                        val w = 1.dp.toPx()
                        drawLine(Color.White.copy(alpha = if (down) 0.04f else 0.15f), Offset(0f, w / 2), Offset(size.width, w / 2), w)
                        drawLine(Color.White.copy(alpha = 0.05f), Offset(w / 2, 0f), Offset(w / 2, size.height), w)
                        drawLine(Color.Black.copy(alpha = 0.5f), Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                        if (!down) {
                            drawRect(
                                Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), endY = size.height * 0.5f,
                                )
                            )
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                if (caption != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (led) PlayLight(latched, dot = 6.dp)
                        // Engraved: a faint highlight just below the letters.
                        Text(
                            caption, fontSize = 9.sp, letterSpacing = 2.sp, color = Sand.Dim,
                            style = TextStyle(shadow = Shadow(Color.White.copy(alpha = 0.12f), Offset(0f, 1.5f))),
                        )
                    }
                }
                KeyGlyph(icon, if (down) Color.White else Sand.White)
            }
            // Front edge: the key's thickness, shrinking as the key sinks.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height((edge - travel).coerceAtLeast(0.dp))
                    .clip(frontShape)
                    .background(frontFace)
                    .drawBehind {
                        drawLine(Color(0xFF2E2E2E), Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
                    }
            )
        }
    }
}

@Composable
private fun KeyGlyph(icon: KeyIcon, color: Color) {
    Canvas(Modifier.size(width = 32.dp, height = 16.dp)) {
        val u = size.height / 16f
        fun tri(from: Float, tip: Float) = Path().apply {
            moveTo(from * u, 2 * u); lineTo(tip * u, 8 * u); lineTo(from * u, 14 * u); close()
        }
        fun draw(c: Color) = when (icon) {
            KeyIcon.Prev -> { drawPath(tri(16f, 7f), c); drawPath(tri(25f, 16f), c) }
            KeyIcon.Next -> { drawPath(tri(7f, 16f), c); drawPath(tri(16f, 25f), c) }
            KeyIcon.PlayPause -> {
                drawPath(tri(3f, 13f), c)
                drawRect(c, Offset(19 * u, 2 * u), Size(3.5f * u, 12 * u))
                drawRect(c, Offset(26 * u, 2 * u), Size(3.5f * u, 12 * u))
            }
        }
        translate(top = 1.dp.toPx()) { draw(Color.White.copy(alpha = 0.1f)) } // engraved highlight
        draw(color)
    }
}

/** Red when playing, grey when paused, with a soft glow that fades in. */
@Composable
fun PlayLight(on: Boolean, modifier: Modifier = Modifier, dot: Dp = 10.dp) {
    val glow by animateFloatAsState(if (on) 1f else 0f, tween(250), label = "playLight")
    Canvas(modifier.size(dot * 2)) {
        val r = dot.toPx() / 2
        drawCircle(Sand.Red.copy(alpha = 0.35f * glow), radius = r * 2)
        drawCircle(lerp(Color(0xFF333333), Sand.Red, glow), radius = r)
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}
