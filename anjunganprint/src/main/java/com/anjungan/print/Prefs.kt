package com.anjungan.print

import android.content.Context

object Prefs {

    private const val PREFS = "anjungan_print_settings"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_PRINTER_MAC = "printer_mac"
    private const val KEY_PAPER_WIDTH = "paper_width"
    private const val KEY_BRAND_NAME = "brand_name"
    private const val KEY_BRAND_SUB = "brand_sub"
    private const val KEY_REQUIRE_PRINTER = "require_printer_for_queue"

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

    fun brandName(context: Context): String =
        prefs(context).getString(KEY_BRAND_NAME, "").orEmpty().trim()

    fun setBrandName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_BRAND_NAME, name.trim()).apply()
    }

    fun brandSub(context: Context): String =
        prefs(context).getString(KEY_BRAND_SUB, "").orEmpty().trim()

    fun setBrandSub(context: Context, sub: String) {
        prefs(context).edit().putString(KEY_BRAND_SUB, sub.trim()).apply()
    }

    /**
     * true = nomor antrian hanya boleh terbit bila printer siap (opsi A),
     * sehingga tidak ada nomor yang terbuang karena tiket tidak keluar.
     * false = nomor tetap terbit walau printer tidak terhubung (opsi B).
     */
    fun requirePrinterForQueue(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUIRE_PRINTER, true)

    fun setRequirePrinterForQueue(context: Context, required: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUIRE_PRINTER, required).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
