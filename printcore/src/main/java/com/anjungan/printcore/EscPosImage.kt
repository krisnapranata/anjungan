package com.anjungan.printcore

import android.graphics.Bitmap

object EscPosImage {

    const val WIDTH_58MM = 384
    const val WIDTH_80MM = 576

    fun rasterCommands(
        bitmap: Bitmap,
        maxWidthDots: Int = WIDTH_58MM,
        dither: Boolean = false
    ): ByteArray {
        val scaled = if (bitmap.width > maxWidthDots) {
            val ratio = maxWidthDots.toFloat() / bitmap.width
            val newH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, maxWidthDots, newH, true)
        } else {
            bitmap
        }

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val bit = if (dither) floydSteinberg(gray, w, h)
        else FloatArray(gray.size) { if (gray[it] < 128f) 0f else 255f }

        val rowBytes = (w + 7) / 8
        val data = ByteArray(rowBytes * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (bit[y * w + x] < 128f) {
                    val idx = y * rowBytes + (x / 8)
                    data[idx] = (data[idx].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }

        val xL = w and 0xFF
        val xH = (w shr 8) and 0xFF
        val yL = h and 0xFF
        val yH = (h shr 8) and 0xFF
        val header = byteArrayOf(
            0x1D, 0x76, 0x30, 0x00,
            xL.toByte(), xH.toByte(), yL.toByte(), yH.toByte()
        )
        return header + data
    }

    private fun floydSteinberg(gray: FloatArray, w: Int, h: Int): FloatArray {
        val out = gray.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                val old = out[idx]
                val new = if (old < 128f) 0f else 255f
                out[idx] = new
                val err = old - new
                if (x + 1 < w) out[idx + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x > 0) out[idx + w - 1] += err * 3f / 16f
                    out[idx + w] += err * 5f / 16f
                    if (x + 1 < w) out[idx + w + 1] += err * 1f / 16f
                }
            }
        }
        return out
    }
}
