package com.anjungan.kiosk

import android.webkit.JavascriptInterface

class KtpBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun scanNik() {
        activity.runOnUiThread { activity.startKtpScan() }
    }
}
