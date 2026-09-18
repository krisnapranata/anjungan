# AGENTS.md

Android kiosk app (single-module Gradle project, package `com.anjungan.kiosk`): a full-screen landscape WebView that loads a server URL, plus USB ESC/POS printing to a Caysn IW-J82BT thermal printer. No README, no tests, no lint config — a successful build is the only verification.

## Build

- Must run Gradle with JDK 17. System default Java is 15 and fails (`Android Gradle plugin requires Java 17`). Use:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug`
- `local.properties` points the SDK to `/home/krisna/Android/Sdk`; it is gitignored and CI relies on the runner's preinstalled SDK.
- Release build: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (CI pushes this artifact on main via `.github/workflows/build-apk.yml`).
- Both debug and release are signed with the checked-in `keystore/debug.keystore` (passwords hardcoded in `app/build.gradle.kts`) — don't change signing without checking CI expectations.

## Architecture

- `MainActivity` — kiosk WebView (JS + DOM storage on, cleartext traffic allowed, UA suffix `AnjunganKiosk/1.0`). Server URL comes from `SettingsStore` (SharedPreferences `anjungan_settings`); if blank, opens `AdminActivity` instead.
- Admin screen is reached by 7 taps within 700 ms (see `TAPS_FOR_ADMIN` in `MainActivity`) or by the web page navigating to `anjungan://admin`.
- JS bridge exposed as `window.PrinterBridge`: `print(text)`, `printTicket(json)`, `printRaw(base64)`, `cut()`, `status()`. Native side pushes status to `window.AnjunganOnPrinterChange(status)`.
- `UsbPrinterManager` only talks to the hardcoded VID 19275 / PID 14384 (also in `res/xml/device_filter.xml`). ESC/POS text is encoded GB18030 (ISO-8859-1 fallback) — don't switch to UTF-8, printer output breaks.
- ViewBinding is enabled; layouts in `res/layout/` generate `ActivityMainBinding`/`ActivityAdminBinding`. `android.nonTransitiveRClass=true` means use the app package's own `R`, never `android.R`.
- UI strings are Indonesian; keep new user-facing text in that language.
