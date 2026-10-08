package com.blacksand.player.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.min
import kotlin.math.pow

/**
 * Turns a cover into dot-matrix art: a grid of ink dots on label paper, each dot as big as
 * that spot of the cover is dark (a halftone). Used on the cassette label in the app and widget.
 */
object DotArt {
    private const val PAPER = 0xFFE9E7E2.toInt()
    private const val INK = 0xFF111111.toInt()

    fun render(cover: Bitmap, cells: Int = 24, sizePx: Int = 240): Bitmap {
        // Centre-crop to a square, then shrink to one pixel per cell to read each cell's brightness.
        val side = min(cover.width, cover.height)
        val square = Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side)
        val small = Bitmap.createScaledBitmap(square, cells, cells, true)

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(PAPER)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
        val cell = sizePx.toFloat() / cells
        for (y in 0 until cells) for (x in 0 until cells) {
            val p = small.getPixel(x, y)
            val luma = (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
            val dark = (1f - luma).pow(0.8f) // lift the mid-tones a little
            val r = dark * cell * 0.55f
            if (r > cell * 0.06f) canvas.drawCircle((x + 0.5f) * cell, (y + 0.5f) * cell, r, paint)
        }
        return out
    }
}
