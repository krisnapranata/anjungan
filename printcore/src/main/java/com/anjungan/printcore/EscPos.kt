package com.anjungan.printcore

import java.nio.charset.Charset

object EscPos {

    val GB18030: Charset = try {
        Charset.forName("GB18030")
    } catch (e: Exception) {
        Charsets.ISO_8859_1
    }

    fun init() = byteArrayOf(0x1B, 0x40)

    fun align(mode: Int) = byteArrayOf(0x1B, 0x61, mode.coerceIn(0, 2).toByte())

    fun bold(on: Boolean) = byteArrayOf(0x1B, 0x45, if (on) 1 else 0)

    fun size(w: Int, h: Int) = byteArrayOf(
        0x1D, 0x21,
        (((w - 1) shl 4) or (h - 1)).coerceIn(0, 255).toByte()
    )

    fun feed(n: Int) = byteArrayOf(0x1B, 0x64, n.coerceIn(0, 255).toByte())

    /** Potong kertas sekali (GS V 1 = partial cut, satu titik tersambung). */
    fun cut() = byteArrayOf(0x1D, 0x56, 0x01)

    fun encode(text: String): ByteArray = text.replace("\r", "").toByteArray(GB18030)

    fun wrap(text: String, max: Int): String {
        val width = if (max <= 0) 48 else max
        return text.split('\n').joinToString("\n") { line ->
            if (line.length <= width) line else line.chunked(width).joinToString("\n")
        }
    }
}
