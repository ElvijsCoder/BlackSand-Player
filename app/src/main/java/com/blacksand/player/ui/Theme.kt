package com.blacksand.player.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.blacksand.player.R

object Sand {
    val Black = Color(0xFF0B0B0B)
    val Body = Color(0xFF161616)
    val Recess = Color(0xFF050505)
    val Key = Color(0xFF1D1D1D)
    val KeyDown = Color(0xFF121212)
    val Line = Color(0xFF262626)
    val White = Color(0xFFF2F2F0)
    val Dim = Color(0xFF8A8A8A)
    val Label = Color(0xFFE9E7E2)
    val Ink = Color(0xFF111111)
    val InkDim = Color(0xFF444444)
    val Red = Color(0xFFD71921)
}

// Stand-ins: Doto ≈ Ndot, Space Grotesk ≈ NType 82, Space Mono for UI text.
// Swapping in the real Nothing fonts later = replace the files in res/font.
val DotFont = FontFamily(Font(R.font.doto, FontWeight.Black))
val TitleFont = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
)
val MonoFont = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

private val mono = TextStyle(fontFamily = MonoFont, fontSize = 14.sp)

@Composable
fun BlackSandTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Sand.Black,
            surface = Sand.Body,
            onBackground = Sand.White,
            onSurface = Sand.White,
            primary = Sand.White,
            onPrimary = Sand.Black,
        ),
        typography = Typography(
            bodyLarge = mono.copy(fontSize = 15.sp),
            bodyMedium = mono,
            bodySmall = mono.copy(fontSize = 12.sp),
            labelLarge = mono.copy(fontWeight = FontWeight.Bold),
        ),
        content = content,
    )
}

// One small random tile, drawn repeated over the content: film grain for almost no cost.
private val grainTile: ImageBitmap by lazy {
    val size = 128
    val rnd = java.util.Random(7)
    val pixels = IntArray(size * size) {
        val v = rnd.nextInt(256)
        (20 shl 24) or (v shl 16) or (v shl 8) or v
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

fun Modifier.grain(): Modifier = drawWithCache {
    val brush = ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated))
    onDrawWithContent {
        drawContent()
        drawRect(brush)
    }
}
