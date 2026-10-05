# AGENTS.md

Multi-module Android project (3 Gradle modules) — kiosk apps that print to a Caysn IW-J82BT thermal printer (USB ESC/POS + Bluetooth). No README, no tests, no lint config — a successful build is the only verification. UI strings are Indonesian; keep new user-facing text in that language.

## Modules

- `app` — WebView kiosk (package `com.anjungan.kiosk`). Full-screen landscape WebView loading a server URL; JS bridge `window.PrinterBridge`. Reached by 7 taps (`TAPS_FOR_ADMIN`) or `anjungan://admin`.
- `anjunganprint` — **native** registration kiosk (package `com.anjungan.print`). No WebView; talks to a Django REST API for patient search, polyclinic/dokter selection, patient registration, and queue-number printing. This is the actively developed module.
- `printcore` — shared Android library (package `com.anjungan.printcore`): `UsbPrinterManager`, `BluetoothPrinterManager`, `EscPos`, `TicketBuilder`, `EscPosImage`, `PrinterTransport`.

## Build

- Must run Gradle with JDK 17. System default Java is 15 and fails (`Android Gradle plugin requires Java 17`). Use:
  `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug`
- Build one module only: `./gradlew :anjunganprint:assembleDebug` (or `:app:assembleDebug`). APK output under `<module>/build/outputs/apk/`.
- `local.properties` points the SDK to `/home/krisna/Android/Sdk`; gitignored, CI uses the runner's SDK.
- All three modules are signed with the checked-in `keystore/debug.keystore` (store/key password `anjungan123`, alias `anjungan`) — don't change signing without checking CI.
- CI (`.github/workflows/build-apk.yml`) builds **only** `app` release on `main` → `app/build/outputs/apk/release/app-release.apk`. It does NOT build `anjunganprint`.

## Architecture notes

- ViewBinding enabled in `app` and `anjunganprint`. `android.nonTransitiveRClass=true`: use each module's own `R`, never `android.R`.
- ESC/POS text is encoded GB18030 (ISO-8859-1 fallback) — do NOT switch to UTF-8; printer output breaks. Hardcoded printer VID 19275 / PID 14384 (`printcore/.../UsbPrinterManager.kt` and `res/xml/device_filter*.xml`).
- `anjunganprint` printer managers (USB + Bluetooth) and reconnect loop live in `anjunganprint/.../MainActivity.kt`; server URL stored in `Prefs` (SharedPreferences `anjungan_print_settings`), same for printer MAC and paper width (32/48 cols).

## `anjunganprint` ↔ Django API

- The app depends on a **separate repo**: Django REST API at `/home/krisna/iDRG/casemix/casemixiDRG/` (project `casemixiDRG`). Its venv is `/home/krisna/iDRG/casemix/venv`; use `source .../venv/bin/activate && python manage.py ...`.
- Android calls: `registrasi/cek-pasien/`, `api/pilih-poli/`, `api/pilih-dokter/`, `api/registrasi-simpan/`, `api/pasien-baru/`, `api/antrian-baru/` — all defined in `registrasi/views/reg_mandiri_api.py` (routes in `registrasi/urls.py`). Response shape: `{"status": "ok"|"error", "pesan": ..., ...payload}`.
- Two MySQL DBs (`idrg/db_routers.py`): `default` (Khanza tables: `pasien`, `reg_periksa`, etc.) and `customsik` (custom models). Models tagged `@use_customsik` + `managed = True` route to `customsik`; Khanza tables are `managed = False`.
- `no_rkm_medis` (patient RM number) is generated from `MAX(CAST(no_rkm_medis AS UNSIGNED)) + 1` on the `pasien` table, zero-padded to 6 digits — mirrors Khanza's "always use last RM" logic.
- Migrations for `customsik`-only models must be applied with `--database=customsik`, e.g. `python manage.py migrate registrasi --database=customsik`.
- Patient table schema / field names mirror the SIMRS Khanza desktop app at `/home/krisna/SIMRSCustoms/CUTOM TERBARU/SIMRS-Khanza/src/simrskhanza/DlgPasien.java` — consult it for field names, enums, and required columns.

## KTP OCR

- ML Kit text recognition (`KtpOcr.kt` in `anjunganprint`) extracts NIK + 15 fields from the KTP photo. `extractKtpData` handles labels and values split across separate lines (a `pendingKey` fallback) — when editing OCR, preserve that, since ML Kit often splits label and value onto different lines.
- When a patient is not found by NIK, the app offers "Daftar Pasien Baru" (new-patient form pre-filled from OCR). Fields not on the KTP default to `-`; `no_peserta` defaults to the NIK.
- The "Ambil Antrean Pendaftaran Pasien Baru" button checks printer connectivity **before** calling `api/antrian-baru/` (which increments the daily counter) so a disconnected printer doesn't consume a queue number.
