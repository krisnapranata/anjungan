# Default proguard rules
-keep class com.anjungan.kiosk.PrinterBridge { *; }
-keepclassmembers class com.anjungan.kiosk.PrinterBridge {
    @android.webkit.JavascriptInterface <methods>;
}
