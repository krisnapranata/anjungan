package com.anjungan.print

import android.content.Context

object Prefs {

    private const val PREFS = "anjungan_print_settings"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_PRINTER_MAC = "printer_mac"
    private const val KEY_PAPER_WIDTH = "paper_width"

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "").orEmpty().trim()

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SERVER_URL, url.trim()).apply()
    }

    fun printerMac(context: Context): String =
        prefs(context).getString(KEY_PRINTER_MAC, "").orEmpty().trim()

    fun setPrinterMac(context: Context, mac: String) {
        prefs(context).edit().putString(KEY_PRINTER_MAC, mac.trim()).apply()
    }

    /** Kolom per baris: 32 untuk 58mm, 48 untuk 80mm. Default 48 (80mm). */
    fun paperWidth(context: Context): Int =
        prefs(context).getInt(KEY_PAPER_WIDTH, 48)

    fun setPaperWidth(context: Context, width: Int) {
        prefs(context).edit().putInt(KEY_PAPER_WIDTH, width).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
