package com.blacksand.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
    val travel by animateDpAsState(if (down) 4.dp else 0.dp, tween(90), label = "keyTravel")

    val view = LocalView.current
    var wasPressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed != wasPressed) {
            view.performHapticFeedback(
                if (pressed) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.KEYBOARD_RELEASE
            )
            wasPressed = pressed
        }
    }

    val shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
    val face = if (down) {
        Brush.verticalGradient(listOf(Color(0xFF1A1A1A), Color(0xFF141414)))
    } else {
        Brush.verticalGradient(0f to Color(0xFF2C2C2C), 0.35f to Color(0xFF1E1E1E), 1f to Color(0xFF161616))
    }

    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(Color.Black) // the key's shadow, visible below it while raised
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
        Column(
            Modifier
                .fillMaxWidth()
                .height(height - 5.dp)
                .offset(y = travel)
                .clip(shape)
                .background(face)
                .drawBehind { // light catching the top edge
                    if (!down) drawLine(Color(0xFF3C3C3C), Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            if (caption != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (led) PlayLight(latched, dot = 6.dp)
                    Text(caption, fontSize = 9.sp, letterSpacing = 2.sp, color = Sand.Dim)
                }
            }
            KeyGlyph(icon, if (down) Color.White else Sand.White)
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
        when (icon) {
            KeyIcon.Prev -> { drawPath(tri(16f, 7f), color); drawPath(tri(25f, 16f), color) }
            KeyIcon.Next -> { drawPath(tri(7f, 16f), color); drawPath(tri(16f, 25f), color) }
            KeyIcon.PlayPause -> {
                drawPath(tri(3f, 13f), color)
                drawRect(color, Offset(19 * u, 2 * u), Size(3.5f * u, 12 * u))
                drawRect(color, Offset(26 * u, 2 * u), Size(3.5f * u, 12 * u))
            }
        }
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
