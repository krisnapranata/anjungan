package com.anjungan.kiosk

import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

class PrinterBridge(private val printer: UsbPrinterManager) {

    private object esc {
        fun init() = byteArrayOf(0x1B, 0x40)
        fun align(mode: Int) = byteArrayOf(0x1B, 0x61, mode.coerceIn(0, 2).toByte())
        fun bold(on: Boolean) = byteArrayOf(0x1B, 0x45, if (on) 1 else 0)
        fun size(w: Int, h: Int) = byteArrayOf(
            0x1D, 0x21,
            (((w - 1) shl 4) or (h - 1)).coerceIn(0, 255).toByte()
        )
        fun feed(n: Int) = byteArrayOf(0x1B, 0x64, n.coerceIn(0, 255).toByte())
        fun cut() = byteArrayOf(0x1D, 0x56, 0x42, 0x00)
    }

    private val charset: Charset = try {
        Charset.forName("GB18030")
    } catch (e: Exception) {
        Charsets.ISO_8859_1
    }

    private fun encode(text: String): ByteArray = text.replace("\r", "").toByteArray(charset)

    @JavascriptInterface
    fun print(text: String): Boolean {
        if (text.isEmpty()) return false
        val out = ByteArrayOutputStream()
        out.write(esc.init())
        text.split('\n').forEach { line ->
            out.write(encode(line))
            out.write('\n'.code)
        }
        out.write(esc.feed(2))
        return printer.print(out.toByteArray())
    }

    @JavascriptInterface
    fun printTicket(json: String): Boolean {
        return try {
            val ticket = JSONObject(json)
            val width = ticket.optInt("width", 48).coerceIn(16, 64)
            val out = ByteArrayOutputStream()
            out.write(esc.init())

            val title = ticket.optString("title", "")
            if (title.isNotEmpty()) {
                out.write(esc.align(1))
                out.write(esc.size(2, 2))
                wrap(title, width / 2).split('\n').forEach {
                    out.write(encode(it)); out.write('\n'.code)
                }
                out.write(esc.size(1, 1))
            }

            val subtitle = ticket.optString("subtitle", "")
            if (subtitle.isNotEmpty()) {
                out.write(esc.align(1))
                wrap(subtitle, width).split('\n').forEach {
                    out.write(encode(it)); out.write('\n'.code)
                }
            }

            val lines = ticket.optJSONArray("lines")
            if (lines != null) {
                for (i in 0 until lines.length()) {
                    val line = lines.optJSONObject(i) ?: continue
                    val text = line.optString("text", "")
                    if (text.isEmpty()) {
                        out.write('\n'.code)
                        continue
                    }
                    val align = when (line.optString("align", "l").lowercase()) {
                        "c" -> 1
                        "r" -> 2
                        else -> 0
                    }
                    val (w, h) = when (line.optString("size", "normal").lowercase()) {
                        "double", "big" -> 2 to 2
                        "wide" -> 2 to 1
                        "high" -> 1 to 2
                        else -> 1 to 1
                    }
                    out.write(esc.align(align))
                    out.write(esc.bold(line.optBoolean("bold", false)))
                    out.write(esc.size(w, h))
                    wrap(text, width / w).split('\n').forEach {
                        out.write(encode(it)); out.write('\n'.code)
                    }
                    out.write(esc.bold(false))
                    out.write(esc.size(1, 1))
                }
            }

            val footer = ticket.optString("footer", "")
            if (footer.isNotEmpty()) {
                out.write(esc.align(1))
                wrap(footer, width).split('\n').forEach {
                    out.write(encode(it)); out.write('\n'.code)
                }
            }

            out.write(esc.feed(ticket.optInt("feed", 3)))
            if (ticket.optBoolean("cut", true)) out.write(esc.cut())
            printer.print(out.toByteArray())
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun printRaw(base64: String): Boolean {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            bytes.isNotEmpty() && printer.print(bytes)
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun cut(): Boolean {
        val out = ByteArrayOutputStream()
        out.write(esc.feed(3))
        out.write(esc.cut())
        return printer.print(out.toByteArray())
    }

    @JavascriptInterface
    fun status(): String = JSONObject()
        .put("status", printer.status())
        .put("connected", printer.isConnected())
        .put("device", "Caysn IW-J82BT")
        .toString()

    private fun wrap(text: String, max: Int): String {
        val width = if (max <= 0) 48 else max
        return text.split('\n').joinToString("\n") { line ->
            if (line.length <= width) line else line.chunked(width).joinToString("\n")
        }
    }
}
