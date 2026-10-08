package com.blacksand.player.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// --- Reduce motion ----------------------------------------------------------------------

/** True when Android's "Remove animations" is on: the app then uses plain fades and still reels. */
val LocalReduceMotion = staticCompositionLocalOf { false }

fun reduceMotion(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

// --- Nameplate --------------------------------------------------------------------------

/** The brushed-metal badge across the deck, like the maker's plate on a cassette player. */
@Composable
fun Nameplate(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1D1D1D), Color(0xFF121212))))
            .drawBehind {
                // Fine brushed lines, a light top edge and a dark bottom edge.
                val step = 3.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = 0.025f), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
                drawLine(Color.White.copy(alpha = 0.10f), Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
                drawLine(Color.Black.copy(alpha = 0.6f), Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
            }
            .clearAndSetSemantics { contentDescription = "Black Sand stereo cassette player" }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "BLACK SAND",
            fontFamily = TitleFont, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
            fontSize = 19.sp, letterSpacing = (-0.4).sp, color = Sand.White,
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(6.dp).background(Sand.Red))
        Spacer(Modifier.weight(1f))
        Text(
            "STEREO CASSETTE PLAYER\nAUTO STOP · TYPE I",
            fontSize = 8.sp, letterSpacing = 1.8.sp, lineHeight = 12.sp, color = Sand.Dim,
            style = TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.End),
        )
    }
}

// --- Rolling counter --------------------------------------------------------------------

/**
 * A mechanical tape counter: each digit rolls up to the next one, like the number wheels
 * on a deck. Only digits that change move. Static under reduce motion.
 */
@Composable
fun RollingCounter(text: String, fontSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    Row(modifier.semantics { contentDescription = text }) {
        text.forEachIndexed { i, ch ->
            if (reduce || !ch.isDigit()) {
                Text(ch.toString(), fontFamily = DotFont, fontSize = fontSize, color = color)
            } else {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        // Forwards rolls up; when the counter goes back (rewind, new song) it rolls down.
                        val up = targetState > initialState || (initialState == '9' && targetState == '0') ||
                            (initialState == '5' && targetState == '0' && i == 3)
                        val dir = if (up) 1 else -1
                        (slideInVertically(tween(220)) { it * dir } + fadeIn(tween(220)))
                            .togetherWith(slideOutVertically(tween(220)) { -it * dir } + fadeOut(tween(160)))
                            .using(SizeTransform(clip = true))
                    },
                    label = "digit$i",
                ) { d ->
                    Text(d.toString(), fontFamily = DotFont, fontSize = fontSize, color = color)
                }
            }
        }
    }
}

// --- Tape loading ------------------------------------------------------------------------

/** A short moment on launch: two reels spin up under the nameplate while the tape "loads". */
@Composable
fun TapeLoading() {
    val spin by rememberInfiniteTransition(label = "loading").animateFloat(
        0f, 360f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Restart), label = "reels",
    )
    Column(
        Modifier.fillMaxSize().background(Sand.Black).grain(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            repeat(2) { i ->
                Canvas(Modifier.size(64.dp)) {
                    val c = Offset(size.width / 2, size.height / 2)
                    drawCircle(Color(0xFF2A2420), size.minDimension / 2, c)
                    hub(c, spin + i * 55f, size.minDimension * 2.2f)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Nameplate(Modifier.padding(horizontal = 48.dp))
        Spacer(Modifier.height(14.dp))
        Text("LOADING TAPE", color = Sand.Dim, fontSize = 11.sp, letterSpacing = 2.sp)
    }
}
