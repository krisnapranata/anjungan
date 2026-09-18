package com.anjungan.printcore

import org.json.JSONObject
import java.io.ByteArrayOutputStream

object TicketBuilder {

    fun build(ticket: JSONObject): ByteArray {
        val width = ticket.optInt("width", 48).coerceIn(16, 64)
        val out = ByteArrayOutputStream()
        out.write(EscPos.init())

        val title = ticket.optString("title", "")
        if (title.isNotEmpty()) {
            out.write(EscPos.align(1))
            out.write(EscPos.size(2, 2))
            EscPos.wrap(title, width / 2).split('\n').forEach {
                out.write(EscPos.encode(it)); out.write('\n'.code)
            }
            out.write(EscPos.size(1, 1))
        }

        val subtitle = ticket.optString("subtitle", "")
        if (subtitle.isNotEmpty()) {
            out.write(EscPos.align(1))
            EscPos.wrap(subtitle, width).split('\n').forEach {
                out.write(EscPos.encode(it)); out.write('\n'.code)
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
                out.write(EscPos.align(align))
                out.write(EscPos.bold(line.optBoolean("bold", false)))
                out.write(EscPos.size(w, h))
                EscPos.wrap(text, width / w).split('\n').forEach {
                    out.write(EscPos.encode(it)); out.write('\n'.code)
                }
                out.write(EscPos.bold(false))
                out.write(EscPos.size(1, 1))
            }
        }

        val footer = ticket.optString("footer", "")
        if (footer.isNotEmpty()) {
            out.write(EscPos.align(1))
            EscPos.wrap(footer, width).split('\n').forEach {
                out.write(EscPos.encode(it)); out.write('\n'.code)
            }
        }

        out.write(EscPos.feed(ticket.optInt("feed", 3)))
        if (ticket.optBoolean("cut", true)) out.write(EscPos.cut())
        return out.toByteArray()
    }
}
