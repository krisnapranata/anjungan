package com.anjungan.print

import com.anjungan.printcore.EscPos
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object ReceiptPrinter {

    /** Kolom per baris: 32 (58mm) atau 48 (80mm). */
    fun build(receipt: JSONObject, width: Int = 48): ByteArray {
        val rs = receipt.optString("setting_rs", "SIMRS")
        val noReg = receipt.optString("no_reg", "")
        val noRawat = receipt.optString("no_rawat", "")
        val tgl = receipt.optString("tgl", "")
        val noRm = receipt.optString("no_rm", "")
        val nama = receipt.optString("nama", "")
        val poli = receipt.optString("poli", "")
        val dokter = receipt.optString("dokter", "")
        val penjab = receipt.optString("penjab", "-")
        val status = receipt.optString("status", "")
        val maxValue = (width - 16).coerceAtLeast(8)

        val out = ByteArrayOutputStream()

        fun line(s: String) {
            out.write(EscPos.encode(s))
            out.write('\n'.code)
        }

        fun center(s: String, w: Int = width) = line(padCenter(s, w))

        fun div() = line("=".repeat(width))

        fun divMin() = line("-".repeat(width))

        fun field(label: String, value: String) {
            val v = value.ifBlank { "-" }.take(maxValue)
            val padded = (label + " : ").padEnd(16, ' ')
            val prefix = if (padded.length + v.length > width) label + " : " else padded
            line(prefix + v)
        }

        out.write(EscPos.init())
        out.write(EscPos.align(1))
        center(rs)
        div()
        center("BUKTI REGISTRASI")
        div()
        line("")
        out.write(EscPos.size(2, 2))
        center(noReg, width / 2)
        out.write(EscPos.size(1, 1))
        line("")
        out.write(EscPos.align(0))
        divMin()
        field("No.Registrasi", noReg)
        field("No.Rawat", noRawat)
        field("Tgl", tgl)
        field("No.RM", noRm)
        field("Nama", nama)
        field("Poliklinik", poli)
        if (dokter.isNotEmpty()) field("Dokter", dokter)
        field("Jenis Bayar", penjab)
        field("Status", status)
        divMin()
        out.write(EscPos.align(1))
        center("Terima kasih")
        div()
        line("")
        line("")

        out.write(EscPos.align(0))
        out.write(EscPos.feed(6))
        out.write(EscPos.cut())
        return out.toByteArray()
    }

    private fun padCenter(t: String, width: Int): String {
        val s = t.trim()
        if (s.length >= width) return s.take(width)
        val pad = (width - s.length) / 2
        return " ".repeat(pad) + s
    }
}
