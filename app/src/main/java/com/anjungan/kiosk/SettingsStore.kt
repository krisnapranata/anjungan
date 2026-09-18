package com.anjungan.kiosk

import android.content.Context

object SettingsStore {

    private const val PREFS = "anjungan_settings"
    const val KEY_SERVER_URL = "server_url"
    const val KEY_PRINTER_MAC = "printer_mac"

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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
