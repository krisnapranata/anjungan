package com.anjungan.kiosk

import android.content.Context

object SettingsStore {

    private const val PREFS = "anjungan_settings"
    const val KEY_SERVER_URL = "server_url"

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "").orEmpty().trim()

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SERVER_URL, url.trim()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
