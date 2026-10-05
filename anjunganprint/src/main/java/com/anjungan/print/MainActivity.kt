package com.anjungan.print

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.anjungan.print.databinding.ActivityMainBinding
import com.anjungan.printcore.BluetoothPrinterManager
import com.anjungan.printcore.EscPos
import com.anjungan.printcore.PrinterTransport
import com.anjungan.printcore.TicketBuilder
import com.anjungan.printcore.UsbPrinterManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class Screen { HOME, CARI, POLI, DOKTER, KONFIRMASI, SUKSES, PENGATURAN }

    private lateinit var binding: ActivityMainBinding
    private lateinit var usb: UsbPrinterManager
    private lateinit var bt: BluetoothPrinterManager
    private lateinit var api: ApiClient
    private val ktpOcr = KtpOcr()

    private var currentScreen = Screen.CARI

    private val poliAdapter = PoliAdapter { onPoliSelected(it) }
    private val dokterAdapter = DokterAdapter { onDokterSelected(it) }

    private var pasien: JSONObject? = null
    private var poliSel: JSONObject? = null
    private var dokterSel: JSONObject? = null
    private var receipt: JSONObject? = null
    private var jamMulaiMillis: Long? = null
    private var blockSimpan = false
    private var perujukRequired = false
    private val penjabKode = mutableListOf<String>()
    private val perujukKodeByName = mutableMapOf<String, String>()
    private var selectedPerujukKode = ""
    private var ktpData: KtpData? = null
    private var antrianBerikutnya = 1

    private val bondedAddresses = mutableListOf<String>()
    private var btPermissionAsked = false
    private var printing = false
    private var autoReconnectEnabled = true

    /**
     * Loop reconnect BT: dipakai agar printer thermal yang dimatikan lalu
     * dihidupkan kembali tersambung otomatis. Berhenti sendiri saat sudah
     * terhubung.
     */
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (!autoReconnectEnabled) return
            if (bt.isConnected()) return
            val mac = Prefs.printerMac(this@MainActivity)
            if (mac.isBlank()) return
            if (!bt.isBluetoothEnabled() || !bt.hasConnectPermission()) {
                handler.postDelayed(this, 5000)
                return
            }
            bt.connect(mac) { ok ->
                if (ok) {
                    updatePrinterStatus()
                } else {
                    handler.postDelayed(this, 5000)
                }
            }
        }
    }

    private fun startReconnectLoop(delayMs: Long = 2000) {
        if (!autoReconnectEnabled) return
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun stopReconnectLoop() {
        handler.removeCallbacks(reconnectRunnable)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, 1000)
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDevice()
                    if (device != null && usb.connect(device)) updatePrinterStatus()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    usb.disconnect()
                    updatePrinterStatus()
                }
                UsbPrinterManager.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    if (granted) {
                        val device = intent.usbDevice()
                        if (usb.connect(device)) {
                            updatePrinterStatus()
                            settingsResult("Printer USB terhubung")
                        }
                    } else {
                        settingsResult("Izin USB ditolak")
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (bt.isConnected() &&
                        System.currentTimeMillis() - bt.lastDisconnectAt > 2000
                    ) {
                        bt.disconnect()
                        updatePrinterStatus()
                        startReconnectLoop()
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val mac = Prefs.printerMac(this@MainActivity)
                    if (mac.isBlank()) return@onReceive
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null && device.address == mac && !bt.isConnected()) {
                        startReconnectLoop(1000)
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE, -1
                        ) == BluetoothAdapter.STATE_ON
                    ) {
                        loadBondedDevices()
                    }
                }
            }
        }
    }

    private val btPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            loadBondedDevices()
        }

    private val ktpScanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val nik = result.data?.getStringExtra(KtpScanActivity.EXTRA_NIK)
            if (result.resultCode == RESULT_OK && nik != null) {
                val ktpData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    result.data?.getSerializableExtra(KtpScanActivity.EXTRA_KTP_DATA, KtpData::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    result.data?.getSerializableExtra(KtpScanActivity.EXTRA_KTP_DATA) as? KtpData
                }
                ktpData?.let { this.ktpData = it }
                if (currentScreen == Screen.HOME) {
                    binding.screenHome.root.findViewById<EditText>(R.id.etIdentitasKontrol).setText(nik)
                    doPasienKontrol()
                } else {
                    binding.screenCari.etCari.setText(nik)
                    doCekPasien(nik)
                }
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchKtpScan()
            else showAlertInResult("Izin kamera ditolak")
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onImageForOcr(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableFullScreen()

        usb = UsbPrinterManager(this)
        bt = BluetoothPrinterManager(this)
        api = ApiClient("")

        binding.screenCari.etCari.setOnEditorActionListener { _, _, _ ->
            doCekPasien(binding.screenCari.etCari.text.toString().trim())
            hideKeyboard()
            true
        }
        binding.screenCari.btnCari.setOnClickListener {
            doCekPasien(binding.screenCari.etCari.text.toString().trim())
            hideKeyboard()
        }
        binding.screenCari.etCari.setOnClickListener {
            showKeyboard()
        }
        binding.screenCari.btnFotoKtp.setOnClickListener { ambilAntrianBaru() }
        binding.screenCari.btnKamera.setOnClickListener { startKtpScan() }
        binding.screenCari.btnPengaturan.setOnClickListener { openPengaturan() }

        setupHomeScreen()

        binding.screenPoli.rvPoli.layoutManager = LinearLayoutManager(this)
        binding.screenPoli.rvPoli.adapter = poliAdapter
        binding.screenPoli.btnBackFromPoli.setOnClickListener { resetFlow() }

        binding.screenDokter.rvDokter.layoutManager = LinearLayoutManager(this)
        binding.screenDokter.rvDokter.adapter = dokterAdapter
        binding.screenDokter.btnBackFromDokter.setOnClickListener { showScreen(Screen.POLI) }

        binding.screenKonfirmasi.btnBatalKonfirmasi.setOnClickListener { resetFlow() }
        binding.screenKonfirmasi.btnSimpanCetak.setOnClickListener { doSimpan() }
        binding.screenKonfirmasi.btnTambahPerujuk.setOnClickListener { dialogTambahPerujuk() }

        binding.screenSukses.btnCetak.setOnClickListener { printReceiptNow() }
        binding.screenSukses.btnSelesai.setOnClickListener { resetFlow() }

        setupPengaturan()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbPrinterManager.ACTION_USB_PERMISSION)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    Screen.HOME -> finish()
                    Screen.CARI -> showScreen(Screen.HOME)
                    Screen.POLI -> resetFlow()
                    Screen.DOKTER -> showScreen(Screen.POLI)
                    Screen.KONFIRMASI -> showScreen(Screen.DOKTER)
                    Screen.SUKSES -> resetFlow()
                    Screen.PENGATURAN -> {
                        if (api.baseUrl().isNotBlank()) showScreen(Screen.HOME) else finish()
                    }
                }
            }
        })

        autoConnectUsb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !bt.hasConnectPermission() && !btPermissionAsked
        ) {
            btPermissionAsked = true
            btPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            loadBondedDevices()
            autoConnectBluetoothFromPrefs()
        }
        updatePrinterStatus()

        if (Prefs.printerMac(this).isNotBlank()) {
            startReconnectLoop(3000)
        }

        if (Prefs.serverUrl(this).isBlank()) {
            showScreen(Screen.PENGATURAN)
        } else if (savedInstanceState != null) {
            restoreState(savedInstanceState)
            val restored = runCatching {
                Screen.valueOf(savedInstanceState.getString("screen") ?: Screen.HOME.name)
            }.getOrDefault(Screen.HOME)
            showScreen(restored)
            binding.screenKonfirmasi.btnSimpanCetak.isEnabled = !blockSimpan
            binding.screenKonfirmasi.btnSimpanCetak.text =
                getString(R.string.btn_simpan_cetak)
            updateCountdown()
            // Daftar poli/dokter di adapter hanya di memori, jadi setelah rotasi
            // activity dibuat ulang dan adapter kosong. Muat ulang dari server.
            when (restored) {
                Screen.POLI -> if (pasien != null) loadPoliScreen() else showScreen(Screen.HOME)
                Screen.DOKTER ->
                    if (pasien != null && poliSel != null) loadDokterScreen()
                    else showScreen(Screen.HOME)
                Screen.KONFIRMASI ->
                    if (pasien != null && poliSel != null && dokterSel != null) {
                        loadKonfirmasiScreen()
                    } else {
                        showScreen(Screen.HOME)
                    }
                Screen.SUKSES -> renderReceipt(receipt)
                else -> Unit
            }
        } else {
            showScreen(Screen.HOME)
        }
        // Angka antrian hanya ditampilkan setelah dibaca dari server, supaya
        // tidak sempat muncul nomor lokal yang ternyata sudah dipakai.
        binding.screenHome.root.findViewById<TextView>(R.id.tvAntrianNext)?.text = "---"
        loadAntrianStatus()
        handler.post(ticker)
        handler.post(clockRunnable)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pasien", pasien?.toString())
        outState.putString("poli", poliSel?.toString())
        outState.putString("dokter", dokterSel?.toString())
        outState.putString("receipt", receipt?.toString())
        outState.putString("screen", currentScreen.name)
        outState.putLong("jamMulai", jamMulaiMillis ?: -1L)
        outState.putBoolean("blockSimpan", blockSimpan)
        outState.putBoolean("perujukRequired", perujukRequired)
        outState.putString("perujukKode", selectedPerujukKode)
        outState.putStringArrayList("penjabKode", ArrayList(penjabKode))
        outState.putString(
            "perujukMap",
            JSONObject(perujukKodeByName as Map<*, *>).toString()
        )
        outState.putInt("antrianBerikutnya", antrianBerikutnya)
    }

    private fun restoreState(saved: Bundle) {
        pasien = saved.getString("pasien")?.let { runCatching { JSONObject(it) }.getOrNull() }
        poliSel = saved.getString("poli")?.let { runCatching { JSONObject(it) }.getOrNull() }
        dokterSel = saved.getString("dokter")?.let { runCatching { JSONObject(it) }.getOrNull() }
        receipt = saved.getString("receipt")?.let { runCatching { JSONObject(it) }.getOrNull() }
        jamMulaiMillis = saved.getLong("jamMulai").takeIf { it >= 0L }
        blockSimpan = saved.getBoolean("blockSimpan")
        perujukRequired = saved.getBoolean("perujukRequired")
        selectedPerujukKode = saved.getString("perujukKode").orEmpty()
        antrianBerikutnya = saved.getInt("antrianBerikutnya", 1)
        saved.getStringArrayList("penjabKode")?.let { penjabKode.clear(); penjabKode.addAll(it) }
        runCatching {
            saved.getString("perujukMap")?.let { JSONObject(it) }.let { m ->
                perujukKodeByName.clear()
                m?.keys()?.forEach { k -> perujukKodeByName[k] = m.optString(k) }
            }
        }
    }

    override fun onDestroy() {
        stopReconnectLoop()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(clockRunnable)
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        usb.disconnect()
        bt.disconnect()
        super.onDestroy()
    }

    // ---------- API base url ----------

    private fun ApiClient.baseUrl(): String =
        Prefs.serverUrl(this@MainActivity).let { url ->
            if (url.isBlank()) url else if (url.endsWith("/")) url else "$url/"
        }

    private fun api(): ApiClient = ApiClient(api.baseUrl())

    // ---------- Navigasi layar ----------

    private fun showScreen(screen: Screen) {
        currentScreen = screen
        binding.screenHome.root.visibility = if (screen == Screen.HOME) View.VISIBLE else View.GONE
        binding.screenCari.root.visibility = if (screen == Screen.CARI) View.VISIBLE else View.GONE
        binding.screenPoli.root.visibility = if (screen == Screen.POLI) View.VISIBLE else View.GONE
        binding.screenDokter.root.visibility = if (screen == Screen.DOKTER) View.VISIBLE else View.GONE
        binding.screenKonfirmasi.root.visibility =
            if (screen == Screen.KONFIRMASI) View.VISIBLE else View.GONE
        binding.screenSukses.root.visibility = if (screen == Screen.SUKSES) View.VISIBLE else View.GONE
        binding.screenPengaturan.root.visibility =
            if (screen == Screen.PENGATURAN) View.VISIBLE else View.GONE
    }

    private fun resetFlow() {
        pasien = null
        poliSel = null
        dokterSel = null
        receipt = null
        jamMulaiMillis = null
        blockSimpan = false
        selectedPerujukKode = ""
        ktpData = null

        binding.screenCari.etCari.setText("")
        binding.screenCari.llResult.removeAllViews()

        val homeRoot = binding.screenHome.root
        homeRoot.findViewById<EditText>(R.id.etIdentitasKontrol).setText("")
        homeRoot.findViewById<LinearLayout>(R.id.llHasilPasien).apply {
            removeAllViews()
            visibility = View.GONE
        }

        showScreen(Screen.HOME)
        loadAntrianStatus()
    }

    // ---------- Cari pasien ----------

    private fun doCekPasien(q: String) {
        val result = binding.screenCari.llResult
        if (q.isBlank()) {
            result.removeAllViews()
            result.addView(
                alert(
                    R.drawable.bg_alert_warning, R.color.alert_warning_fg,
                    "Masukkan No.RM / NIK KTP / Nama / Alamat / No.HP / Nama Ibu / No.Peserta"
                )
            )
            return
        }
        showLoadingCari(true)
        api().get(
            "registrasi/cek-pasien/?q=" + URLEncoder.encode(q, "UTF-8")
        ) { ok, json ->
            showLoadingCari(false)
            if (!ok || json == null) {
                showAlertInResult("Gagal menghubungi server")
                return@get
            }
            when (json.optString("status")) {
                "ok" -> {
                    if (json.has("pasien_list")) {
                        val arr = json.optJSONArray("pasien_list")
                        if (arr == null || arr.length() == 0) {
                            showPasienNotFound(json.optString("pesan", "Data pasien tidak ditemukan"))
                        } else if (arr.length() == 1) {
                            renderSingle(arr.getJSONObject(0))
                        } else {
                            renderMulti(arr)
                        }
                    } else {
                        renderSingle(json)
                    }
                }
                "tagihan" -> showAlertInResult(
                    json.optString("pesan", "Pasien memiliki tagihan yang belum di-closing.")
                )
                else -> showPasienNotFound(
                    json.optString("pesan", "Data pasien tidak ditemukan.")
                )
            }
        }
    }

    private fun showPasienNotFound(pesan: String) {
        val result = binding.screenCari.llResult
        result.removeAllViews()
        result.addView(alert(R.drawable.bg_alert_danger, R.color.alert_danger_fg, pesan))

        if (ktpData != null) {
            val daftar = primaryButton("Daftar Pasien Baru")
            daftar.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            daftar.setOnClickListener { showDialogPasienBaru() }
            result.addView(daftar)
        }
    }

    private fun showDialogPasienBaru() {
        val ktp = ktpData ?: return
        val view = layoutInflater.inflate(R.layout.dialog_pasien_baru, null)

        val etNik = view.findViewById<EditText>(R.id.etNik)
        val etNama = view.findViewById<EditText>(R.id.etNama)
        val etTmpLahir = view.findViewById<EditText>(R.id.etTmpLahir)
        val etTglLahir = view.findViewById<EditText>(R.id.etTglLahir)
        val spJk = view.findViewById<Spinner>(R.id.spJk)
        val spGolDarah = view.findViewById<Spinner>(R.id.spGolDarah)
        val spAgama = view.findViewById<Spinner>(R.id.spAgama)
        val spSttsNikah = view.findViewById<Spinner>(R.id.spSttsNikah)
        val etPekerjaan = view.findViewById<EditText>(R.id.etPekerjaan)
        val spPnd = view.findViewById<Spinner>(R.id.spPnd)
        val etAlamat = view.findViewById<EditText>(R.id.etAlamat)
        val etRtRw = view.findViewById<EditText>(R.id.etRtRw)
        val etKelDesa = view.findViewById<EditText>(R.id.etKelDesa)
        val etKecamatan = view.findViewById<EditText>(R.id.etKecamatan)
        val etKabKota = view.findViewById<EditText>(R.id.etKabKota)
        val etProvinsi = view.findViewById<EditText>(R.id.etProvinsi)
        val etNmIbu = view.findViewById<EditText>(R.id.etNmIbu)
        val etNamaPj = view.findViewById<EditText>(R.id.etNamaPj)
        val spKeluarga = view.findViewById<Spinner>(R.id.spKeluarga)
        val etPekerjaanPj = view.findViewById<EditText>(R.id.etPekerjaanPj)
        val etAlamatPj = view.findViewById<EditText>(R.id.etAlamatPj)
        val etKelurahanPj = view.findViewById<EditText>(R.id.etKelurahanPj)
        val etKecamatanPj = view.findViewById<EditText>(R.id.etKecamatanPj)
        val etKabupatenPj = view.findViewById<EditText>(R.id.etKabupatenPj)
        val etPropinsiPj = view.findViewById<EditText>(R.id.etPropinsiPj)
        val etNoTlp = view.findViewById<EditText>(R.id.etNoTlp)
        val etNoPeserta = view.findViewById<EditText>(R.id.etNoPeserta)
        val etNip = view.findViewById<EditText>(R.id.etNip)
        val etEmail = view.findViewById<EditText>(R.id.etEmail)
        val spSukuBangsa = view.findViewById<Spinner>(R.id.spSukuBangsa)
        val spBahasa = view.findViewById<Spinner>(R.id.spBahasa)
        val spCacatFisik = view.findViewById<Spinner>(R.id.spCacatFisik)
        val etPerusahaan = view.findViewById<EditText>(R.id.etPerusahaan)
        val spPenjab = view.findViewById<Spinner>(R.id.spPenjab)
        val btnBatal = view.findViewById<Button>(R.id.btnBatal)
        val btnSimpan = view.findViewById<Button>(R.id.btnSimpanPasien)

        etNik.setText(ktp.nik)
        etNama.setText(ktp.nama)
        etTmpLahir.setText(ktp.tmpLahir)
        etTglLahir.setText(ktp.tglLahir)
        etAlamat.setText(ktp.alamat)
        etRtRw.setText(ktp.rtRw)
        etKelDesa.setText(ktp.kelDesa)
        etKecamatan.setText(ktp.kecamatan)
        etKabKota.setText(ktp.kabKota)
        etProvinsi.setText(ktp.provinsi)
        etPekerjaan.setText(ktp.pekerjaan)
        etNoPeserta.setText(ktp.nik)
        etNmIbu.setText("-")
        etNamaPj.setText("-")
        etPekerjaanPj.setText("-")
        etAlamatPj.setText("-")
        etKelurahanPj.setText("-")
        etKecamatanPj.setText("-")
        etKabupatenPj.setText("-")
        etPropinsiPj.setText("-")
        etNoTlp.setText("-")
        etNip.setText("-")
        etEmail.setText("-")
        etPerusahaan.setText("-")

        setupSpinner(spJk, R.array.jk_array, if (ktp.jk == "L") 0 else if (ktp.jk == "P") 1 else 0)
        setupSpinner(spGolDarah, R.array.gol_darah_array, when (ktp.golDarah.uppercase()) {
            "A" -> 0; "B" -> 1; "AB" -> 2; "O" -> 3; else -> 4
        })
        setupSpinner(spAgama, R.array.agama_array, when (ktp.agama.uppercase()) {
            "ISLAM" -> 0; "KRISTEN" -> 1; "KATOLIK" -> 2; "HINDU" -> 3;
            "BUDHA" -> 4; "BUDDHA" -> 4; "KONG HU CHU" -> 5; else -> 6
        })
        setupSpinner(spSttsNikah, R.array.stts_nikah_array, when (ktp.sttsNikah) {
            "MENIKAH" -> 0; "BELUM MENIKAH" -> 1; "JANDA" -> 2; "DUDHA" -> 3; else -> 4
        })
        setupSpinner(spPnd, R.array.pnd_array, 0)
        setupSpinner(spKeluarga, R.array.keluarga_array, 6)
        setupSpinner(spSukuBangsa, R.array.suku_bangsa_array, 0)
        setupSpinner(spBahasa, R.array.bahasa_array, 0)
        setupSpinner(spCacatFisik, R.array.cacat_fisik_array, 0)

        val penjabAdapter = ArrayAdapter.createFromResource(
            this, R.array.penjab_array, android.R.layout.simple_spinner_dropdown_item
        )
        spPenjab.adapter = penjabAdapter
        spPenjab.setSelection(0)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .create()

        btnBatal.setOnClickListener { dialog.dismiss() }
        btnSimpan.setOnClickListener {
            simpanPasienBaru(
                etNik.text.toString(),
                etNama.text.toString(),
                etTmpLahir.text.toString(),
                etTglLahir.text.toString(),
                if (spJk.selectedItemPosition == 0) "L" else "P",
                spGolDarah.selectedItem.toString(),
                spAgama.selectedItem.toString(),
                spSttsNikah.selectedItem.toString(),
                etPekerjaan.text.toString(),
                spPnd.selectedItem.toString(),
                etAlamat.text.toString(),
                etRtRw.text.toString(),
                etKelDesa.text.toString(),
                etKecamatan.text.toString(),
                etKabKota.text.toString(),
                etProvinsi.text.toString(),
                etNmIbu.text.toString(),
                etNamaPj.text.toString(),
                spKeluarga.selectedItem.toString(),
                etPekerjaanPj.text.toString(),
                etAlamatPj.text.toString(),
                etKelurahanPj.text.toString(),
                etKecamatanPj.text.toString(),
                etKabupatenPj.text.toString(),
                etPropinsiPj.text.toString(),
                etNoTlp.text.toString(),
                etNoPeserta.text.toString(),
                etNip.text.toString(),
                etEmail.text.toString(),
                spSukuBangsa.selectedItem.toString(),
                spBahasa.selectedItem.toString(),
                spCacatFisik.selectedItem.toString(),
                etPerusahaan.text.toString(),
                spPenjab.selectedItem.toString()
            ) {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun setupSpinner(spinner: Spinner, arrayRes: Int, position: Int) {
        val adapter = ArrayAdapter.createFromResource(
            this, arrayRes, android.R.layout.simple_spinner_dropdown_item
        )
        spinner.adapter = adapter
        spinner.setSelection(position)
    }

    private fun simpanPasienBaru(
        nik: String,
        nama: String,
        tmpLahir: String,
        tglLahir: String,
        jk: String,
        golDarah: String,
        agama: String,
        sttsNikah: String,
        pekerjaan: String,
        pnd: String,
        alamat: String,
        rtRw: String,
        kelDesa: String,
        kecamatan: String,
        kabKota: String,
        provinsi: String,
        nmIbu: String,
        namaPj: String,
        keluarga: String,
        pekerjaanPj: String,
        alamatPj: String,
        kelurahanPj: String,
        kecamatanPj: String,
        kabupatenPj: String,
        propinsiPj: String,
        noTlp: String,
        noPeserta: String,
        nip: String,
        email: String,
        sukuBangsa: String,
        bahasa: String,
        cacatFisik: String,
        perusahaan: String,
        penjab: String,
        onDone: () -> Unit
    ) {
        showLoadingCari(true)
        api().postForm(
            "registrasi/api/pasien-baru/",
            mapOf(
                "no_ktp" to nik,
                "nm_pasien" to nama,
                "tmp_lahir" to tmpLahir,
                "tgl_lahir" to tglLahir,
                "jk" to jk,
                "gol_darah" to golDarah,
                "agama" to agama,
                "stts_nikah" to sttsNikah,
                "pekerjaan" to pekerjaan,
                "pnd" to pnd,
                "alamat" to alamat,
                "nm_kel" to kelDesa,
                "nm_kec" to kecamatan,
                "nm_kab" to kabKota,
                "nm_prop" to provinsi,
                "nm_ibu" to nmIbu,
                "namakeluarga" to namaPj,
                "keluarga" to keluarga,
                "pekerjaanpj" to pekerjaanPj,
                "alamatpj" to alamatPj,
                "kelurahanpj" to kelurahanPj,
                "kecamatanpj" to kecamatanPj,
                "kabupatenpj" to kabupatenPj,
                "propinsipj" to propinsiPj,
                "no_tlp" to noTlp,
                "no_peserta" to noPeserta,
                "nip" to nip,
                "email" to email,
                "suku_bangsa" to sukuBangsa,
                "bahasa_pasien" to bahasa,
                "cacat_fisik" to cacatFisik,
                "perusahaan_pasien" to perusahaan,
                "kd_pj" to penjab
            )
        ) { ok, json ->
            showLoadingCari(false)
            if (!ok || json == null) {
                showAlertInResult("Gagal menyimpan data pasien")
                return@postForm
            }
            when (json.optString("status")) {
                "ok" -> {
                    val noRkm = json.optString("no_rkm_medis", "")
                    if (noRkm.isNotBlank()) {
                        pasien = JSONObject()
                            .put("no_rkm_medis", noRkm)
                            .put("nm_pasien", nama)
                        binding.screenCari.etCari.setText(noRkm)
                        onDone()
                        loadPoliScreen()
                    } else {
                        showAlertInResult("Gagal menyimpan data pasien")
                    }
                }
                else -> showAlertInResult(
                    json.optString("pesan", "Gagal menyimpan data pasien")
                )
            }
        }
    }

    private fun ambilAntrianBaru() {
        val wajibPrinter = Prefs.requirePrinterForQueue(this)
        val siapCetak = activeTransport() != null

        if (wajibPrinter && !siapCetak) {
            showAlertInResult("Printer belum terhubung. Hubungkan lewat Pengaturan.")
            return
        }
        if (printing) {
            showAlertInResult("Printer sedang memproses…")
            return
        }

        val nomorAntrian = antrianBerikutnya
        val nomorStr = String.format("%03d", nomorAntrian)

        showLoadingCari(true)
        val client = api()
        client.get("registrasi/api/antrian-baru/") { ok, json ->
            showLoadingCari(false)
            if (!ok || json == null) {
                showAlertInResult("Gagal mengambil nomor antrian\n" + pesanGagal(client, json))
                return@get
            }
            when (json.optString("status")) {
                "ok" -> {
                    val nomor = json.optString("nomor", nomorStr)
                    antrianBerikutnya = json.optInt("berikutnya", nomorAntrian + 1)
                    updateAntrianDisplay()
                    if (activeTransport() != null && !printing) {
                        cetakAntrian(nomor)
                    } else {
                        showAlertInResult(getString(R.string.msg_antrian_tidak_cetak, nomor))
                    }
                }
                else -> showAlertInResult(
                    json.optString("pesan", "Gagal mengambil nomor antrian")
                )
            }
        }
    }

    private fun updateAntrianDisplay() {
        val nomorStr = String.format("%03d", antrianBerikutnya)
        binding.screenCari.tvAntrianBerikutnya.text = "Antrian Baru Berikutnya: $nomorStr"
        binding.screenHome.root.findViewById<TextView>(R.id.tvAntrianNext)?.text = nomorStr
    }

    /**
     * Ambil nomor antrian berikutnya dari server (tanpa incremented) supaya angka
     * yang tampil di dashboard selalu sama dengan yang akan dipakai server.
     */
    private fun loadAntrianStatus() {
        if (Prefs.serverUrl(this).isBlank()) return
        api().get("registrasi/api/antrian-status/") { ok, json ->
            if (!ok || json == null || json.optString("status") != "ok") return@get
            val berikutnya = json.optInt("berikutnya", 0)
            if (berikutnya <= 0) return@get
            antrianBerikutnya = berikutnya
            updateAntrianDisplay()
        }
    }

    private fun cetakAntrian(nomor: String) {
        val transport = activeTransport()
        if (transport == null) {
            showAlertInResult("Printer belum terhubung. Hubungkan lewat Pengaturan.")
            return
        }
        if (printing) {
            showAlertInResult("Printer sedang memproses…")
            return
        }
        printing = true

        val width = Prefs.paperWidth(this)
        val dash = "-".repeat(width)
        val now = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val ticket = JSONObject()
            .put("width", width)
            .put("title", "ANJUNGAN MANDIRI")
            .put("subtitle", "PENDAFTARAN PASIEN BARU")
            .put(
                "lines", JSONArray()
                    .put(JSONObject().put("text", dash).put("align", "c"))
                    .put(JSONObject().put("text", "No. Antrian: $nomor").put("align", "c").put("bold", true))
                    .put(JSONObject().put("text", dash).put("align", "c"))
                    .put(JSONObject().put("text", "Pasien Baru").put("align", "c"))
                    .put(JSONObject().put("text", dash).put("align", "c"))
                    .put(JSONObject().put("text", now).put("align", "c"))
                    .put(JSONObject().put("text", dash).put("align", "c"))
            )
            .put("footer", "AnjunganMandiri")
            .put("feed", 3)
            .put("cut", true)

        runPrint(TicketBuilder.build(ticket)) { ok ->
            printing = false
            if (!ok) {
                showAlertInResult("Gagal mencetak. Cek printer di Pengaturan.")
            }
        }
    }

    private fun showLoadingCari(show: Boolean) {
        binding.screenCari.pbCari.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showAlertInResult(text: String) {
        val result = binding.screenCari.llResult
        result.removeAllViews()
        result.addView(alert(R.drawable.bg_alert_danger, R.color.alert_danger_fg, text))
    }

    private fun renderSingle(p: JSONObject) {
        pasien = p
        val result = binding.screenCari.llResult
        result.removeAllViews()

        if (p.optBoolean("masih_inap")) {
            result.addView(
                alert(
                    R.drawable.bg_alert_warning, R.color.alert_warning_fg,
                    "Perhatian: Pasien masih dalam perawatan di kamar inap dan belum dipulangkan."
                )
            )
        }

        val reg = p.optJSONObject("reg_hari_ini")
        if (reg != null) {
            result.addView(buildRegisteredBox(reg))
        }

        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_light_box)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }
        val headRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val headCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headCol.addView(
            TextView(this).apply {
                text = "Pasien Ditemukan"
                setTextColor(color(R.color.ink))
                textSize = 15f
                setTypeface(Typeface.DEFAULT_BOLD)
            }
        )
        headCol.addView(
            TextView(this).apply {
                text = "Verifikasi data sebelum melanjutkan"
                setTextColor(color(R.color.muted))
                textSize = 11f
            }
        )
        headRow.addView(headCol)
        infoCard.addView(headRow)
        infoCard.addView(infoRow("No. RM", p.optString("no_rkm_medis", "-")))
        infoCard.addView(infoRow("Nama", p.optString("nm_pasien", "-")))
        infoCard.addView(infoRow("NIK KTP", p.optString("no_ktp", "-")))
        infoCard.addView(infoRow("No. Peserta", p.optString("no_peserta", "-")))
        infoCard.addView(infoRow("Alamat", p.optString("alamat", "-")))
        infoCard.addView(infoRow("No. HP", p.optString("no_tlp", "-")))
        infoCard.addView(infoRow("Nama Ibu", p.optString("nm_ibu", "-")))
        result.addView(infoCard)

        val lanjut = primaryButton("Lanjut Pilih Poliklinik")
        lanjut.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        lanjut.setOnClickListener { loadPoliScreen() }
        result.addView(lanjut)

        val cariLain = outlineButton("Cari Pasien Lain")
        cariLain.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        cariLain.setOnClickListener {
            binding.screenCari.etCari.setText("")
            result.removeAllViews()
        }
        result.addView(cariLain)
    }

    private fun buildRegisteredBox(reg: JSONObject): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_light_box)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }
        box.addView(
            TextView(this).apply {
                text = "Sudah Terdaftar Hari Ini"
                setTextColor(color(R.color.success))
                textSize = 14f
                setTypeface(Typeface.DEFAULT_BOLD)
            }
        )
        box.addView(infoRow("No. Registrasi", reg.optString("no_reg", "-"), boldValue = true))
        box.addView(infoRow("Poliklinik", reg.optString("nm_poli", "-")))
        val dokter = reg.optString("nm_dokter", "")
        if (dokter.isNotEmpty()) box.addView(infoRow("Dokter", dokter))
        box.addView(infoRow("Jam", reg.optString("jam_reg", "-")))

        val btn = primaryButton("Cetak Bukti")
        btn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        btn.setOnClickListener { fetchReceipt(reg.optString("no_rawat", "")) }
        box.addView(btn)

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        box.layoutParams = lp
        return box
    }

    private fun renderMulti(arr: JSONArray) {
        val result = binding.screenCari.llResult
        result.removeAllViews()

        result.addView(
            TextView(this).apply {
                text = "Ditemukan ${arr.length()} pasien"
                setTextColor(color(R.color.muted))
                textSize = 12f
            }
        )

        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_item)
                val pad = dp(12)
                setPadding(pad, pad, pad, pad)
            }

            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            nameCol.addView(
                TextView(this).apply {
                    text = p.optString("nm_pasien", "-")
                    setTextColor(color(R.color.ink))
                    textSize = 15f
                    setTypeface(Typeface.DEFAULT_BOLD)
                }
            )
            nameCol.addView(
                TextView(this).apply {
                    text = p.optString("no_rkm_medis", "-")
                    setTextColor(color(R.color.muted))
                    textSize = 11f
                }
            )
            topRow.addView(nameCol)

            val badgeCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            if (p.optBoolean("masih_inap")) {
                badgeCol.addView(chip("Inap", R.color.alert_warning_fg))
            }
            val reg = p.optJSONObject("reg_hari_ini")
            if (reg != null) {
                badgeCol.addView(chip("Terdaftar", R.color.success))
            }
            topRow.addView(badgeCol)
            card.addView(topRow)

            card.addView(infoRow("NIK KTP", p.optString("no_ktp", "-")))
            card.addView(infoRow("No. Peserta", p.optString("no_peserta", "-")))
            card.addView(infoRow("Alamat", p.optString("alamat", "-")))
            card.addView(infoRow("No. HP", p.optString("no_tlp", "-")))

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            if (reg != null) {
                val bukti = outlineButton("Bukti")
                bukti.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                bukti.setOnClickListener { fetchReceipt(reg.optString("no_rawat", "")) }
                btnRow.addView(bukti)
            }
            val pilih = primaryButton("Pilih")
            pilih.setOnClickListener {
                pasien = p
                loadPoliScreen()
            }
            btnRow.addView(pilih)
            card.addView(btnRow)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            card.layoutParams = lp
            result.addView(card)
        }

        val cariLagi = outlineButton("Cari Lagi")
        cariLagi.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        cariLagi.setOnClickListener {
            binding.screenCari.etCari.setText("")
            result.removeAllViews()
        }
        result.addView(cariLagi)
    }

    // ---------- KTP OCR ----------

    private fun startKtpScan() {
        if (ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            launchKtpScan()
        } else {
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun launchKtpScan() {
        ktpScanLauncher.launch(Intent(this, KtpScanActivity::class.java))
    }

    private fun onImageForOcr(uri: Uri) {
        showLoadingCari(true)
        ktpOcr.readKtp(this, uri) { ktp ->
            showLoadingCari(false)
            if (ktp == null) {
                showAlertInResult("Data KTP tidak terbaca. Coba foto ulang.")
                return@readKtp
            }
            ktpData = ktp
            binding.screenCari.etCari.setText(ktp.nik)
            doCekPasien(ktp.nik)
        }
    }

    private fun renderDetectedNiks(niks: List<String>) {
        val result = binding.screenCari.llResult
        result.removeAllViews()

        result.addView(
            TextView(this).apply {
                text = "NIK yang terbaca dari KTP (klik untuk cari manual):"
                setTextColor(color(R.color.muted))
                textSize = 12f
            }
        )

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        niks.forEach { nik ->
            chips.addView(
                chip(nik, R.color.blue_grad_end, clickable = true).apply {
                    setOnClickListener {
                        binding.screenCari.etCari.setText(nik)
                        doCekPasien(nik)
                    }
                }
            )
        }
        result.addView(chips)

        val cariUlang = outlineButton("Cari Ulang")
        cariUlang.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        cariUlang.setOnClickListener {
            binding.screenCari.etCari.setText("")
            result.removeAllViews()
        }
        result.addView(cariUlang)
    }

    // ---------- Poli & dokter ----------

    private fun loadPoliScreen() {
        val p = pasien ?: return
        showScreen(Screen.POLI)
        binding.screenPoli.tvPoliPatient.text =
            "${p.optString("nm_pasien", "")} — ${p.optString("no_rkm_medis", "")}"
        binding.screenPoli.pbPoli.visibility = View.VISIBLE
        binding.screenPoli.rvPoli.visibility = View.GONE
        binding.screenPoli.tvPoliEmpty.visibility = View.GONE
        binding.screenPoli.tvPoliError.visibility = View.GONE

        api().get(
            "registrasi/api/pilih-poli/?no_rkm=" +
                URLEncoder.encode(p.optString("no_rkm_medis", ""), "UTF-8")
        ) { ok, json ->
            binding.screenPoli.pbPoli.visibility = View.GONE
            if (!ok || json == null) {
                binding.screenPoli.tvPoliError.text = "Gagal menghubungi server"
                binding.screenPoli.tvPoliError.visibility = View.VISIBLE
                return@get
            }
            if (json.optString("status") != "ok") {
                binding.screenPoli.tvPoliError.text = json.optString("pesan", "Gagal memuat poli")
                binding.screenPoli.tvPoliError.visibility = View.VISIBLE
                return@get
            }
            val list = json.optJSONArray("poli_list") ?: JSONArray()
            val items = mutableListOf<JSONObject>()
            for (i in 0 until list.length()) items.add(list.getJSONObject(i))
            poliAdapter.items = items
            poliAdapter.now = System.currentTimeMillis()
            if (items.isEmpty()) {
                binding.screenPoli.tvPoliEmpty.visibility = View.VISIBLE
                binding.screenPoli.rvPoli.visibility = View.GONE
            } else {
                binding.screenPoli.rvPoli.visibility = View.VISIBLE
            }
        }
    }

    private fun onPoliSelected(item: JSONObject) {
        poliSel = item
        loadDokterScreen()
    }

    private fun loadDokterScreen() {
        val p = pasien ?: return
        val poli = poliSel ?: return
        showScreen(Screen.DOKTER)
        binding.screenDokter.tvDokterPatient.text =
            "${p.optString("nm_pasien", "")} — ${poli.optString("nm_poli", "")}"
        binding.screenDokter.pbDokter.visibility = View.VISIBLE
        binding.screenDokter.rvDokter.visibility = View.GONE
        binding.screenDokter.tvDokterEmpty.visibility = View.GONE
        binding.screenDokter.tvDokterError.visibility = View.GONE

        api().get(
            "registrasi/api/pilih-dokter/?no_rkm=" +
                URLEncoder.encode(p.optString("no_rkm_medis", ""), "UTF-8") +
                "&kd_poli=" + URLEncoder.encode(poli.optString("kd_poli", ""), "UTF-8")
        ) { ok, json ->
            binding.screenDokter.pbDokter.visibility = View.GONE
            if (!ok || json == null) {
                binding.screenDokter.tvDokterError.text = "Gagal menghubungi server"
                binding.screenDokter.tvDokterError.visibility = View.VISIBLE
                return@get
            }
            if (json.optString("status") != "ok") {
                binding.screenDokter.tvDokterError.text =
                    json.optString("pesan", "Gagal memuat dokter")
                binding.screenDokter.tvDokterError.visibility = View.VISIBLE
                return@get
            }
            val list = json.optJSONArray("dokter_list") ?: JSONArray()
            val items = mutableListOf<JSONObject>()
            for (i in 0 until list.length()) items.add(list.getJSONObject(i))
            dokterAdapter.items = items
            if (items.isEmpty()) {
                binding.screenDokter.tvDokterEmpty.visibility = View.VISIBLE
                binding.screenDokter.rvDokter.visibility = View.GONE
            } else {
                binding.screenDokter.rvDokter.visibility = View.VISIBLE
            }
        }
    }

    private fun onDokterSelected(item: JSONObject) {
        dokterSel = item
        loadKonfirmasiScreen()
    }

    // ---------- Konfirmasi ----------

    private fun loadKonfirmasiScreen() {
        val p = pasien ?: return
        val poli = poliSel ?: return
        val dokter = dokterSel ?: return
        showScreen(Screen.KONFIRMASI)
        binding.screenKonfirmasi.llAlerts.removeAllViews()
        binding.screenKonfirmasi.boxCountdown.visibility = View.GONE
        binding.screenKonfirmasi.tvKonfirmasiError.visibility = View.GONE
        binding.screenKonfirmasi.btnSimpanCetak.isEnabled = true

        api().get(
            "registrasi/api/registrasi-detail/?no_rkm=" +
                URLEncoder.encode(p.optString("no_rkm_medis", ""), "UTF-8") +
                "&kd_poli=" + URLEncoder.encode(poli.optString("kd_poli", ""), "UTF-8") +
                "&kd_dokter=" + URLEncoder.encode(dokter.optString("kd_dokter", ""), "UTF-8")
        ) { ok, json ->
            if (!ok || json == null) {
                binding.screenKonfirmasi.tvKonfirmasiError.text = "Gagal menghubungi server"
                binding.screenKonfirmasi.tvKonfirmasiError.visibility = View.VISIBLE
                return@get
            }
            if (json.optString("status") != "ok") {
                binding.screenKonfirmasi.tvKonfirmasiError.text =
                    json.optString("pesan", "Gagal memuat data")
                binding.screenKonfirmasi.tvKonfirmasiError.visibility = View.VISIBLE
                return@get
            }
            fillKonfirmasi(json)
        }
    }

    private fun fillKonfirmasi(json: JSONObject) {
        val alerts = binding.screenKonfirmasi.llAlerts
        alerts.removeAllViews()

        if (json.optBoolean("belum_pulang")) {
            alerts.addView(
                alert(
                    R.drawable.bg_alert_warning, R.color.alert_warning_fg,
                    "Perhatian: Pasien masih dalam perawatan di kamar inap dan belum dipulangkan."
                )
            )
        }

        val dup = json.optJSONObject("duplikat_hari_ini")
        if (dup != null) {
            alerts.addView(
                alert(
                    R.drawable.bg_alert_danger, R.color.alert_danger_fg,
                    "Duplikat! Pasien sudah terdaftar di poli ${dup.optString("nm_poli")} " +
                        "dengan dokter ${dup.optString("nm_dokter")} hari ini " +
                        "(No.Reg: ${dup.optString("no_reg")}). Silahkan pilih dokter atau poli lain."
                )
            )
        }

        val kunjungan = json.optJSONArray("kunjungan_lain_info")
        if (kunjungan != null && kunjungan.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until kunjungan.length()) {
                val k = kunjungan.getJSONObject(i)
                sb.append("• ${k.optString("poli")} — ${k.optString("dokter")} " +
                    "(No.Reg: ${k.optString("no_reg")})\n")
            }
            sb.append("Kunjungan sebelumnya akan dibatalkan dan diganti dengan pendaftaran ini.")
            alerts.addView(alert(R.drawable.bg_alert_info, R.color.alert_info_fg, sb.toString()))
        }

        blockSimpan = json.optBoolean("belum_pulang") || dup != null
        jamMulaiMillis = parseJamMulai(json.optString("jam_mulai", ""))

        val pasienObj = json.optJSONObject("pasien") ?: JSONObject()
        binding.screenKonfirmasi.tvNoRm.text = pasienObj.optString("no_rkm_medis", "-")
        binding.screenKonfirmasi.tvNama.text = pasienObj.optString("nm_pasien", "-")
        binding.screenKonfirmasi.tvNik.text = pasienObj.optString("no_ktp", "-")
        binding.screenKonfirmasi.tvAlamat.text = pasienObj.optString("alamat_lengkap", "-")
        binding.screenKonfirmasi.tvPoli.text = json.optJSONObject("poli")?.optString("nm_poli") ?: "-"
        binding.screenKonfirmasi.tvDokter.text =
            json.optJSONObject("dokter")?.optString("nm_dokter") ?: "-"

        val statusPoli = json.optString("status_poli", "Baru")
        binding.screenKonfirmasi.tvStatusPoli.text = statusPoli
        binding.screenKonfirmasi.tvStatusPoli.setTextColor(
            color(if (statusPoli == "Lama") R.color.success else R.color.alert_info_fg)
        )

        penjabKode.clear()
        val names = mutableListOf<String>()
        val penjabArr = json.optJSONArray("penjab_list") ?: JSONArray()
        val defaultKd = json.optString("kd_pj_default", "4")
        var defaultIdx = 0
        for (i in 0 until penjabArr.length()) {
            val pj = penjabArr.getJSONObject(i)
            penjabKode.add(pj.optString("kd_pj", ""))
            names.add(pj.optString("png_jawab", ""))
            if (pj.optString("kd_pj") == defaultKd) defaultIdx = i
        }
        binding.screenKonfirmasi.spPenjab.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )
        if (names.isNotEmpty()) binding.screenKonfirmasi.spPenjab.setSelection(defaultIdx)

        perujukKodeByName.clear()
        val perujukNames = mutableListOf<String>()
        val perujukArr = json.optJSONArray("perujuk_list") ?: JSONArray()
        for (i in 0 until perujukArr.length()) {
            val pj = perujukArr.getJSONObject(i)
            val nama = shortenPuskesmas(pj.optString("nama", ""))
            perujukKodeByName[nama] = pj.optString("kode", "")
            perujukNames.add(nama)
        }
        perujukRequired = perujukNames.isNotEmpty()
        binding.screenKonfirmasi.actPerujuk.setAdapter(
            ArrayAdapter(this, R.layout.item_dropdown_small, perujukNames)
        )
        binding.screenKonfirmasi.actPerujuk.setText("")
        binding.screenKonfirmasi.tvErrPerujuk.visibility = View.GONE

        if (jamMulaiMillis != null) {
            binding.screenKonfirmasi.tvJamMulai.text =
                "Jadwal Dokter: ${json.optString("jam_mulai")}"
            binding.screenKonfirmasi.boxCountdown.visibility = View.VISIBLE
        } else {
            binding.screenKonfirmasi.btnSimpanCetak.isEnabled = !blockSimpan
        }
        updateCountdown()
    }

    private fun parseJamMulai(jam: String): Long? {
        if (jam.isEmpty()) return null
        val parts = jam.split(":")
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun updateCountdown() {
        if (currentScreen != Screen.KONFIRMASI) return
        val mulai = jamMulaiMillis ?: return
        val box = binding.screenKonfirmasi.boxCountdown
        val buka = mulai - 3_600_000
        val now = System.currentTimeMillis()

        if (now >= buka) {
            box.setBackgroundResource(R.drawable.bg_alert_info)
            binding.screenKonfirmasi.boxDigits.visibility = View.GONE
            binding.screenKonfirmasi.tvCountdownStatus.visibility = View.VISIBLE
            binding.screenKonfirmasi.tvCountdownStatus.text = "(registrasi sudah dibuka)"
            binding.screenKonfirmasi.tvCountdownStatus.setTextColor(color(R.color.alert_info_fg))
            binding.screenKonfirmasi.btnSimpanCetak.isEnabled = !blockSimpan
            return
        }

        binding.screenKonfirmasi.boxDigits.visibility = View.VISIBLE
        binding.screenKonfirmasi.tvCountdownStatus.visibility = View.GONE
        val diff = buka - now
        val h = diff / 3_600_000
        val m = (diff % 3_600_000) / 60_000
        val s = (diff % 60_000) / 1000
        binding.screenKonfirmasi.tvCdH.text = String.format(Locale.US, "%02d", h)
        binding.screenKonfirmasi.tvCdM.text = String.format(Locale.US, "%02d", m)
        binding.screenKonfirmasi.tvCdS.text = String.format(Locale.US, "%02d", s)

        if (diff <= 3_600_000) {
            box.setBackgroundResource(R.drawable.bg_alert_warning)
        } else {
            box.setBackgroundResource(R.drawable.bg_alert_danger)
        }
        binding.screenKonfirmasi.btnSimpanCetak.isEnabled = false
    }

    private fun doSimpan() {
        val p = pasien ?: return
        val poli = poliSel ?: return
        val dokter = dokterSel ?: return

        binding.screenKonfirmasi.tvKonfirmasiError.visibility = View.GONE

        if (perujukRequired) {
            selectedPerujukKode = perujukKodeByName[
                binding.screenKonfirmasi.actPerujuk.text.toString().trim()
            ] ?: ""
            if (selectedPerujukKode.isBlank()) {
                binding.screenKonfirmasi.tvErrPerujuk.visibility = View.VISIBLE
                binding.screenKonfirmasi.actPerujuk.requestFocus()
                return
            }
            binding.screenKonfirmasi.tvErrPerujuk.visibility = View.GONE
        }

        val kdPj = penjabKode.getOrNull(
            binding.screenKonfirmasi.spPenjab.selectedItemPosition
        ) ?: ""

        val noRkm = p.optString("no_rkm_medis", "")
        val kdPoli = poli.optString("kd_poli", "")
        val kdDokter = dokter.optString("kd_dokter", "")
        if (noRkm.isBlank() || kdPoli.isBlank()) {
            binding.screenKonfirmasi.tvKonfirmasiError.text =
                "Data pasien/poliklinik tidak lengkap. Silakan ulangi pencarian."
            binding.screenKonfirmasi.tvKonfirmasiError.visibility = View.VISIBLE
            return
        }

        binding.screenKonfirmasi.btnSimpanCetak.isEnabled = false
        binding.screenKonfirmasi.btnSimpanCetak.text = "Memproses…"

        val client = api()
        client.postForm(
            "registrasi/api/registrasi-simpan/",
            mapOf(
                "no_rkm" to noRkm,
                "kd_poli" to kdPoli,
                "kd_dokter" to kdDokter,
                "kd_pj" to kdPj,
                "asal_rujukan" to selectedPerujukKode,
            )
        ) { ok, json ->
            binding.screenKonfirmasi.btnSimpanCetak.isEnabled = true
            binding.screenKonfirmasi.btnSimpanCetak.text =
                getString(R.string.btn_simpan_cetak)
            if (!ok || json == null) {
                binding.screenKonfirmasi.tvKonfirmasiError.text =
                    "Gagal menghubungi server\n" + pesanGagal(client, json)
                binding.screenKonfirmasi.tvKonfirmasiError.visibility = View.VISIBLE
                return@postForm
            }
            if (json.optString("status") != "ok") {
                binding.screenKonfirmasi.tvKonfirmasiError.text =
                    json.optString("pesan", "Gagal menyimpan registrasi")
                binding.screenKonfirmasi.tvKonfirmasiError.visibility = View.VISIBLE
                return@postForm
            }
            receipt = json.optJSONObject("receipt")
            renderReceipt(receipt)
            showScreen(Screen.SUKSES)
        }
    }

    // ---------- Bukti / cetak ----------

    private fun fetchReceipt(noRawat: String) {
        if (noRawat.isBlank()) return
        showLoadingCari(true)
        api().get(
            "registrasi/api/receipt/" + URLEncoder.encode(noRawat, "UTF-8") + "/"
        ) { ok, json ->
            showLoadingCari(false)
            if (!ok || json == null || json.optString("status") != "ok") {
                showAlertInResult(json?.optString("pesan") ?: "Gagal memuat bukti")
                return@get
            }
            receipt = json.optJSONObject("receipt")
            renderReceipt(receipt)
            showScreen(Screen.SUKSES)
        }
    }

    private fun renderReceipt(r: JSONObject?) {
        if (r == null) return
        val s = binding.screenSukses
        s.tvRsName.text = r.optString("setting_rs", "SIMRS")
        s.tvNoRegBig.text = r.optString("no_reg", "-")
        s.tvNoReg.text = r.optString("no_reg", "-")
        s.tvNoRawat.text = r.optString("no_rawat", "-")
        s.tvTgl.text = r.optString("tgl", "-")
        s.tvNoRm.text = r.optString("no_rm", "-")
        s.tvNama.text = r.optString("nama", "-")
        s.tvPoli.text = r.optString("poli", "-")
        val dokter = r.optString("dokter", "")
        s.rowDokter.visibility = if (dokter.isEmpty()) View.GONE else View.VISIBLE
        s.tvDokter.text = dokter
        s.tvPenjab.text = r.optString("penjab", "-")
        s.tvStatus.text = r.optString("status", "-")
        s.tvPrintStatus.text = ""
    }

    private fun printReceiptNow() {
        val r = receipt
        if (r == null) {
            binding.screenSukses.tvPrintStatus.text = "Belum ada data bukti"
            return
        }
        val transport = activeTransport()
        if (transport == null) {
            binding.screenSukses.tvPrintStatus.text =
                "Printer belum terhubung. Hubungkan lewat Pengaturan."
            return
        }
        if (printing) {
            binding.screenSukses.tvPrintStatus.text = "Printer sedang memproses…"
            return
        }
        binding.screenSukses.tvPrintStatus.text = "Mencetak…"
        printing = true

        val bytes = ReceiptPrinter.build(r, Prefs.paperWidth(this))
        when (transport) {
            is BluetoothPrinterManager -> transport.printEnsured(bytes) { ok ->
                printing = false
                binding.screenSukses.tvPrintStatus.text =
                    if (ok) "Berhasil dicetak ke printer thermal"
                    else "Gagal mencetak. Cek printer di Pengaturan."
            }
            is UsbPrinterManager -> transport.printEnsured(bytes) { ok ->
                printing = false
                binding.screenSukses.tvPrintStatus.text =
                    if (ok) "Berhasil dicetak ke printer thermal"
                    else "Gagal mencetak. Cek printer di Pengaturan."
            }
            else -> Thread {
                val ok = try {
                    transport.print(bytes)
                } catch (e: Exception) {
                    false
                }
                runOnUiThread {
                    printing = false
                    binding.screenSukses.tvPrintStatus.text =
                        if (ok) "Berhasil dicetak ke printer thermal"
                        else "Gagal mencetak. Cek printer di Pengaturan."
                }
            }.start()
        }
    }

    /**
     * Cetak di thread background. Untuk Bluetooth pakai printEnsured
     * (reconnect + retry otomatis bila link putus setelah cetak sebelumnya).
     */
    private fun runPrint(bytes: ByteArray, cb: (Boolean) -> Unit) {
        if (printing) {
            cb(false)
            return
        }
        val transport = activeTransport()
        if (transport == null) {
            cb(false)
            return
        }
        printing = true
        if (transport is BluetoothPrinterManager) {
            transport.printEnsured(bytes) { ok ->
                printing = false
                cb(ok)
            }
        } else if (transport is UsbPrinterManager) {
            transport.printEnsured(bytes) { ok ->
                printing = false
                cb(ok)
            }
        } else {
            Thread {
                val ok = try {
                    transport.print(bytes)
                } catch (e: Exception) {
                    false
                }
                runOnUiThread {
                    printing = false
                    cb(ok)
                }
            }.start()
        }
    }

    // ---------- Pengaturan & printer ----------

    private fun openPengaturan() {
        binding.screenPengaturan.etServerUrl.setText(Prefs.serverUrl(this))
        binding.screenPengaturan.etBrandName.setText(Prefs.brandName(this))
        binding.screenPengaturan.etBrandSub.setText(Prefs.brandSub(this))
        binding.screenPengaturan.rgAntrianBaru.check(
            if (Prefs.requirePrinterForQueue(this)) {
                R.id.rbWajibPrinter
            } else {
                R.id.rbTanpaPrinter
            }
        )
        binding.screenPengaturan.spPaperWidth.setSelection(
            if (Prefs.paperWidth(this) == 48) 1 else 0
        )
        updatePrinterStatus()
        showScreen(Screen.PENGATURAN)
    }

    /**
     * Baca dulu jumlah nomor hari ini, tampilkan di dialog konfirmasi, dan baru
     * hapus kalau pengguna menekan Ya. Penghapusan tidak bisa dibatalkan.
     */
    private fun konfirmasiResetAntrian() {
        val tgl = SimpleDateFormat("dd-MM-yyyy", Locale("id", "ID")).format(Date())
        binding.screenPengaturan.btnResetAntrian.isEnabled = false
        api().get("registrasi/api/antrian-status/") { ok, json ->
            binding.screenPengaturan.btnResetAntrian.isEnabled = true
            if (!ok || json == null || json.optString("status") != "ok") {
                showDialogPesan(
                    getString(R.string.reset_antrian_gagal, "Gagal menghubungi server")
                )
                return@get
            }
            val total = json.optInt("total_hari_ini", 0)
            if (total <= 0) {
                showDialogPesan("Belum ada nomor antrian hari ini untuk dihapus.")
                return@get
            }
            val pesan = getString(R.string.reset_antrian_konfirmasi, tgl, total)
            AlertDialog.Builder(this)
                .setTitle(R.string.label_reset_antrian)
                .setMessage(pesan)
                .setPositiveButton("Hapus") { _, _ -> doResetAntrian(tgl) }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    private fun doResetAntrian(tgl: String) {
        binding.screenPengaturan.btnResetAntrian.isEnabled = false
        val client = api()
        client.postForm("registrasi/api/antrian-reset/", emptyMap()) { ok, json ->
            binding.screenPengaturan.btnResetAntrian.isEnabled = true
            if (!ok || json == null || json.optString("status") != "ok") {
                showDialogPesan(
                    getString(R.string.reset_antrian_gagal, pesanGagal(client, json))
                )
                return@postForm
            }
            val dihapus = json.optInt("dihapus", 0)
            showDialogPesan(getString(R.string.reset_antrian_selesai, dihapus, tgl))
            antrianBerikutnya = 1
            binding.screenHome.root.findViewById<TextView>(R.id.tvAntrianNext)?.text =
                String.format("%03d", antrianBerikutnya)
            updateAntrianDisplay()
        }
    }

    private fun showDialogPesan(pesan: String) {
        AlertDialog.Builder(this)
            .setMessage(pesan)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupPengaturan() {
        val pg = binding.screenPengaturan
        pg.btnSaveServer.setOnClickListener {
            val url = pg.etServerUrl.text.toString().trim()
            Prefs.setServerUrl(this, url)
            settingsResult("Tersimpan")
            if (url.isBlank()) {
                Toast.makeText(this, "Alamat server masih kosong", Toast.LENGTH_LONG).show()
            }
        }
        pg.btnSaveBrand.setOnClickListener {
            val name = pg.etBrandName.text.toString().trim()
            val sub = pg.etBrandSub.text.toString().trim()
            Prefs.setBrandName(this, name)
            Prefs.setBrandSub(this, sub)
            settingsResult("Tersimpan")
        }
        pg.btnResetAntrian.setOnClickListener { konfirmasiResetAntrian() }
        pg.rgAntrianBaru.setOnCheckedChangeListener { _, checkedId ->
            Prefs.setRequirePrinterForQueue(this, checkedId == R.id.rbWajibPrinter)
        }
        pg.spPaperWidth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                Prefs.setPaperWidth(this@MainActivity, if (position == 1) 48 else 32)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        pg.btnConnectUsb.setOnClickListener { connectUsb() }
        pg.btnRefreshBt.setOnClickListener { loadBondedDevices() }
        pg.btnConnectBt.setOnClickListener { connectBluetooth() }
        pg.btnDisconnect.setOnClickListener {
            autoReconnectEnabled = false
            stopReconnectLoop()
            usb.disconnect()
            bt.disconnect()
            updatePrinterStatus()
            settingsResult("Koneksi diputus")
        }
        pg.btnTestPrint.setOnClickListener { testPrint() }
        pg.btnFeed.setOnClickListener { feedPaper() }
        pg.btnCut.setOnClickListener { cutPaper() }
        pg.btnKembaliPengaturan.setOnClickListener {
            if (Prefs.serverUrl(this).isNotBlank()) {
                showScreen(Screen.HOME)
            } else {
                Toast.makeText(this, "Isi alamat server dulu", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun settingsResult(msg: String) {
        binding.screenPengaturan.tvSettingsResult.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun activeTransport(): PrinterTransport? = when {
        usb.isConnected() -> usb
        bt.isConnected() -> bt
        else -> null
    }

    private fun autoConnectUsb() {
        val dev = usb.findPrinter() ?: return
        if (usb.hasPermission(dev)) {
            usb.connect(dev)
        } else {
            usb.requestPermission(dev)
        }
    }

    private fun connectUsb() {
        val dev = usb.findPrinter()
        if (dev == null) {
            settingsResult("Printer USB tidak ditemukan")
            updatePrinterStatus()
            return
        }
        if (usb.hasPermission(dev)) {
            if (usb.connect(dev)) {
                settingsResult("Printer USB terhubung")
            } else {
                settingsResult("Gagal membuka koneksi printer")
            }
        } else {
            usb.requestPermission(dev)
            settingsResult("Izinkan akses USB pada dialog")
        }
        updatePrinterStatus()
    }

    private fun loadBondedDevices() {
        bondedAddresses.clear()
        val devices = bt.bondedDevices()
        devices.forEach { bondedAddresses.add(it.address) }
        val names = devices.map { "${it.name ?: "Tanpa nama"} (${it.address})" }
        if (names.isEmpty()) {
            binding.screenPengaturan.spBonded.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                listOf("Tidak ada perangkat terpasang (pair dulu)")
            )
        } else {
            binding.screenPengaturan.spBonded.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, names
            )
        }
        updatePrinterStatus()
    }

    private fun autoConnectBluetoothFromPrefs() {
        val mac = Prefs.printerMac(this)
        if (mac.isBlank()) return
        if (!bt.isBluetoothEnabled()) return
        if (!bt.hasConnectPermission()) return
        bt.connect(mac) { updatePrinterStatus() }
    }

    private fun connectBluetooth() {
        if (!bt.isBluetoothEnabled()) {
            settingsResult("Bluetooth mati. Nyalakan dulu.")
            return
        }
        if (!bt.hasConnectPermission()) {
            btPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val pos = binding.screenPengaturan.spBonded.selectedItemPosition
        if (pos !in bondedAddresses.indices) {
            settingsResult("Pilih printer dulu (atau pair di pengaturan Android)")
            loadBondedDevices()
            return
        }
        binding.screenPengaturan.btnConnectBt.isEnabled = false
        autoReconnectEnabled = true
        bt.connect(bondedAddresses[pos]) { ok ->
            binding.screenPengaturan.btnConnectBt.isEnabled = true
            if (ok) {
                Prefs.setPrinterMac(this, bondedAddresses[pos])
            }
            updatePrinterStatus()
            settingsResult(
                if (ok) "Printer Bluetooth terhubung" else "Gagal konek Bluetooth"
            )
        }
    }

    private fun testPrint() {
        if (activeTransport() == null) {
            settingsResult("Printer belum terhubung")
            return
        }
        if (printing) {
            settingsResult("Printer sedang memproses…")
            return
        }
        val now = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val width = Prefs.paperWidth(this)
        val dash = "-".repeat(width)
        val ticket = JSONObject()
            .put("width", width)
            .put("title", "ANJUNGAN MANDIRI")
            .put("subtitle", "TEST PRINT")
            .put(
                "lines", JSONArray()
                    .put(JSONObject().put("text", dash).put("align", "c"))
                    .put(JSONObject().put("text", "Printer Caysn IW-J82BT").put("align", "c"))
                    .put(JSONObject().put("text", "Status: OK").put("align", "c").put("bold", true))
                    .put(JSONObject().put("text", dash).put("align", "c"))
                    .put(JSONObject().put("text", now).put("align", "c"))
            )
            .put("footer", "AnjunganMandiri - Test")
            .put("feed", 3)
            .put("cut", true)
        runPrint(TicketBuilder.build(ticket)) { ok ->
            settingsResult(if (ok) "Test print berhasil" else "Test print GAGAL")
        }
    }

    private fun feedPaper() {
        if (activeTransport() == null) {
            settingsResult("Printer belum terhubung")
            return
        }
        if (printing) {
            settingsResult("Printer sedang memproses…")
            return
        }
        val out = ByteArrayOutputStream().apply { write(EscPos.feed(5)) }
        runPrint(out.toByteArray()) { ok ->
            settingsResult(if (ok) "Feed OK" else "Feed gagal")
        }
    }

    private fun cutPaper() {
        if (activeTransport() == null) {
            settingsResult("Printer belum terhubung")
            return
        }
        if (printing) {
            settingsResult("Printer sedang memproses…")
            return
        }
        val out = ByteArrayOutputStream().apply {
            write(EscPos.feed(6))
            write(EscPos.cut())
        }
        runPrint(out.toByteArray()) { ok ->
            settingsResult(if (ok) "Cut OK" else "Cut gagal")
        }
    }

    private fun updatePrinterStatus() {
        val dev = usb.findPrinter()
        binding.screenPengaturan.tvUsbStatus.text = when (usb.status()) {
            "connected" -> "USB: TERHUBUNG"
            "not_found" -> "USB: TIDAK DITEMUKAN"
            "permission_denied" -> "USB: ditemukan, izin belum diberikan"
            else -> "USB: terputus"
        } + if (dev != null) " [${dev.deviceName}]" else ""

        binding.screenPengaturan.tvBtStatus.text = when (bt.status()) {
            "connected" -> "Bluetooth: TERHUBUNG" +
                (bt.connectedName?.let { " [$it]" } ?: "")
            "bluetooth_off" -> "Bluetooth: mati di perangkat"
            "permission_denied" -> "Bluetooth: izin belum diberikan"
            else -> "Bluetooth: terputus"
        }
    }

    private fun shortenPuskesmas(name: String): String {
        val s = name.trim()
        return when {
            s.startsWith("UPT ", true) -> "UPT " + shortenPuskesmas(s.substring(4))
            s.startsWith("PUSKESMAS ", true) -> "P. " + s.substring(10)
            s.startsWith("PUSKESMAS", true) -> "P." + s.substring(9)
            else -> s
        }
    }

    private fun dialogTambahPerujuk() {
        val input = EditText(this).apply {
            hint = "Contoh: PUSKESMAS MANTANG"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Tambah Puskesmas")
            .setView(input)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val nama = input.text.toString().trim()
                if (nama.isEmpty()) {
                    Toast.makeText(this, "Nama wajib diisi", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                api().postForm(
                    "registrasi/api/simpan-perujuk/",
                    mapOf("nama" to nama)
                ) { ok, json ->
                    if (ok && json != null && json.optBoolean("ok")) {
                        val kode = json.optString("kode", "")
                        val namaBaru = shortenPuskesmas(json.optString("nama", nama))
                        perujukKodeByName[namaBaru] = kode
                        binding.screenKonfirmasi.actPerujuk.setText(namaBaru)
                        selectedPerujukKode = kode
                        binding.screenKonfirmasi.tvErrPerujuk.visibility = View.GONE
                        dialog.dismiss()
                    } else {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        Toast.makeText(
                            this, "Gagal: ${json?.optString("error") ?: "server error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        dialog.show()
    }

    // ---------- Ticker ----------

    /**
     * Mode kiosk: sembunyikan status bar & navigation bar, isi layar penuh.
     * Dipanggil ulang tiap activity regain focus, karena membuka dialog atau
     * keyboard soft bisa membuat system bar muncul kembali.
     */
    private fun enableFullScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableFullScreen()
    }

    private fun onTick() {
        when (currentScreen) {
            Screen.POLI -> poliAdapter.now = System.currentTimeMillis()
            Screen.KONFIRMASI -> updateCountdown()
            else -> Unit
        }
    }

    private fun updateClock() {
        val now = Date()
        val dateFormat = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID"))
        val timeFormat = SimpleDateFormat("HH:mm", Locale("id", "ID"))
        binding.screenHome.root.findViewById<TextView>(R.id.tvDate).text = dateFormat.format(now)
        binding.screenHome.root.findViewById<TextView>(R.id.tvTime).text = timeFormat.format(now)
    }

    private fun setupHomeScreen() {
        val homeRoot = binding.screenHome.root
        val brandName = Prefs.brandName(this)
        val brandSub = Prefs.brandSub(this)
        homeRoot.findViewById<TextView>(R.id.tvBrandName).text = brandName.ifBlank { getString(R.string.app_name) }
        homeRoot.findViewById<TextView>(R.id.tvBrandSub).text = brandSub

        homeRoot.findViewById<ImageView>(R.id.btnPasienBaru).setOnClickListener {
            ambilAntrianBaruFromHome()
        }
        homeRoot.findViewById<ImageView>(R.id.btnPasienKontrol).setOnClickListener {
            doPasienKontrol()
        }
        homeRoot.findViewById<EditText>(R.id.etIdentitasKontrol).setOnEditorActionListener { _, _, _ ->
            doPasienKontrol()
            true
        }
        homeRoot.findViewById<ImageView>(R.id.btnKameraKontrol).setOnClickListener {
            startKtpScan()
        }
        homeRoot.findViewById<ImageView>(R.id.btnMjkn).setOnClickListener {
            Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
        }
        homeRoot.findViewById<ImageView>(R.id.btnIterasi).setOnClickListener {
            Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
        }
        homeRoot.findViewById<Button>(R.id.btnPengaturanHome).setOnClickListener {
            openPengaturan()
        }
    }

    private fun doPasienKontrol() {
        val identitas = binding.screenHome.root.findViewById<EditText>(R.id.etIdentitasKontrol).text.toString().trim()
        if (identitas.isBlank()) {
            Toast.makeText(this, getString(R.string.service_control_help), Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboardHome()
        showLoadingCari(true)
        api().get(
            "registrasi/cek-pasien/?q=" + URLEncoder.encode(identitas, "UTF-8")
        ) { ok, json ->
            showLoadingCari(false)
            if (!ok || json == null) {
                showAlertHome("Gagal menghubungi server")
                return@get
            }
            when (json.optString("status")) {
                "ok" -> {
                    if (json.has("pasien_list")) {
                        val arr = json.optJSONArray("pasien_list")
                        if (arr == null || arr.length() == 0) {
                            showPasienNotFoundHome(json.optString("pesan", "Data pasien tidak ditemukan"))
                        } else if (arr.length() == 1) {
                            renderSingleHome(arr.getJSONObject(0))
                        } else {
                            renderMultiHome(arr)
                        }
                    } else {
                        renderSingleHome(json)
                    }
                }
                "tagihan" -> showAlertHome(
                    json.optString("pesan", "Pasien memiliki tagihan yang belum di-closing.")
                )
                else -> showPasienNotFoundHome(
                    json.optString("pesan", "Data pasien tidak ditemukan.")
                )
            }
        }
    }

    private fun renderSingleHome(p: JSONObject) {
        pasien = p
        val homeRoot = binding.screenHome.root
        val llHasil = homeRoot.findViewById<LinearLayout>(R.id.llHasilPasien)
        llHasil.removeAllViews()
        llHasil.visibility = View.VISIBLE

        if (p.optBoolean("masih_inap")) {
            llHasil.addView(
                alert(
                    R.drawable.bg_alert_warning, R.color.alert_warning_fg,
                    "Perhatian: Pasien masih dalam perawatan di kamar inap dan belum dipulangkan."
                )
            )
        }

        val reg = p.optJSONObject("reg_hari_ini")
        if (reg != null) {
            llHasil.addView(buildRegisteredBoxHome(reg))
        }

        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_light_box)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }
        val headRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val headCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headCol.addView(
            TextView(this).apply {
                text = "Pasien Ditemukan"
                setTextColor(color(R.color.ink))
                textSize = 15f
                setTypeface(Typeface.DEFAULT_BOLD)
            }
        )
        headCol.addView(
            TextView(this).apply {
                text = "Verifikasi data sebelum melanjutkan"
                setTextColor(color(R.color.muted))
                textSize = 11f
            }
        )
        headRow.addView(headCol)
        infoCard.addView(headRow)
        infoCard.addView(infoRow("No. RM", p.optString("no_rkm_medis", "-")))
        infoCard.addView(infoRow("Nama", p.optString("nm_pasien", "-")))
        infoCard.addView(infoRow("NIK KTP", p.optString("no_ktp", "-")))
        infoCard.addView(infoRow("No. Peserta", p.optString("no_peserta", "-")))
        infoCard.addView(infoRow("Alamat", p.optString("alamat", "-")))
        infoCard.addView(infoRow("No. HP", p.optString("no_tlp", "-")))
        infoCard.addView(infoRow("Nama Ibu", p.optString("nm_ibu", "-")))
        llHasil.addView(infoCard)

        val lanjut = primaryButton("Lanjut Pilih Poliklinik")
        lanjut.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        lanjut.setOnClickListener { loadPoliScreen() }
        llHasil.addView(lanjut)

        val cariLain = outlineButton("Cari Pasien Lain")
        cariLain.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        cariLain.setOnClickListener {
            llHasil.removeAllViews()
            llHasil.visibility = View.GONE
            homeRoot.findViewById<EditText>(R.id.etIdentitasKontrol).setText("")
        }
        llHasil.addView(cariLain)
    }

    private fun buildRegisteredBoxHome(reg: JSONObject): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_light_box)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }
        box.addView(
            TextView(this).apply {
                text = "Sudah Terdaftar Hari Ini"
                setTextColor(color(R.color.success))
                textSize = 14f
                setTypeface(Typeface.DEFAULT_BOLD)
            }
        )
        box.addView(infoRow("No. Registrasi", reg.optString("no_reg", "-"), boldValue = true))
        box.addView(infoRow("Poliklinik", reg.optString("nm_poli", "-")))
        val dokter = reg.optString("nm_dokter", "")
        if (dokter.isNotEmpty()) box.addView(infoRow("Dokter", dokter))
        box.addView(infoRow("Jam", reg.optString("jam_reg", "-")))

        val btn = primaryButton("Cetak Bukti")
        btn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        btn.setOnClickListener { fetchReceipt(reg.optString("no_rawat", "")) }
        box.addView(btn)

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        box.layoutParams = lp
        return box
    }

    private fun renderMultiHome(arr: JSONArray) {
        val homeRoot = binding.screenHome.root
        val llHasil = homeRoot.findViewById<LinearLayout>(R.id.llHasilPasien)
        llHasil.removeAllViews()
        llHasil.visibility = View.VISIBLE

        llHasil.addView(
            TextView(this).apply {
                text = "Ditemukan ${arr.length()} pasien"
                setTextColor(color(R.color.muted))
                textSize = 12f
            }
        )

        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_item)
                val pad = dp(12)
                setPadding(pad, pad, pad, pad)
            }

            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            nameCol.addView(
                TextView(this).apply {
                    text = p.optString("nm_pasien", "-")
                    setTextColor(color(R.color.ink))
                    textSize = 15f
                    setTypeface(Typeface.DEFAULT_BOLD)
                }
            )
            nameCol.addView(
                TextView(this).apply {
                    text = p.optString("no_rkm_medis", "-")
                    setTextColor(color(R.color.muted))
                    textSize = 11f
                }
            )
            topRow.addView(nameCol)

            val badgeCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            if (p.optBoolean("masih_inap")) {
                badgeCol.addView(chip("Inap", R.color.alert_warning_fg))
            }
            val reg = p.optJSONObject("reg_hari_ini")
            if (reg != null) {
                badgeCol.addView(chip("Terdaftar", R.color.success))
            }
            topRow.addView(badgeCol)
            card.addView(topRow)

            card.addView(infoRow("NIK KTP", p.optString("no_ktp", "-")))
            card.addView(infoRow("No. Peserta", p.optString("no_peserta", "-")))
            card.addView(infoRow("Alamat", p.optString("alamat", "-")))
            card.addView(infoRow("No. HP", p.optString("no_tlp", "-")))

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            if (reg != null) {
                val bukti = outlineButton("Bukti")
                bukti.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                bukti.setOnClickListener { fetchReceipt(reg.optString("no_rawat", "")) }
                btnRow.addView(bukti)
            }
            val pilih = primaryButton("Pilih")
            pilih.setOnClickListener {
                pasien = p
                loadPoliScreen()
            }
            btnRow.addView(pilih)
            card.addView(btnRow)

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            card.layoutParams = lp
            llHasil.addView(card)
        }

        val cariLagi = outlineButton("Cari Lagi")
        cariLagi.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        cariLagi.setOnClickListener {
            llHasil.removeAllViews()
            llHasil.visibility = View.GONE
            homeRoot.findViewById<EditText>(R.id.etIdentitasKontrol).setText("")
        }
        llHasil.addView(cariLagi)
    }

    private fun showPasienNotFoundHome(pesan: String) {
        val homeRoot = binding.screenHome.root
        val llHasil = homeRoot.findViewById<LinearLayout>(R.id.llHasilPasien)
        llHasil.removeAllViews()
        llHasil.visibility = View.VISIBLE
        llHasil.addView(alert(R.drawable.bg_alert_danger, R.color.alert_danger_fg, pesan))

        if (ktpData != null) {
            val daftar = primaryButton("Daftar Pasien Baru")
            daftar.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            daftar.setOnClickListener { showDialogPasienBaru() }
            llHasil.addView(daftar)
        }
    }

    private fun ambilAntrianBaruFromHome() {
        val wajibPrinter = Prefs.requirePrinterForQueue(this)
        val siapCetak = activeTransport() != null

        // Opsi A: printer wajib siap dulu, supaya nomor tidak terbuang.
        if (wajibPrinter && !siapCetak) {
            showAlertHome("Printer belum terhubung. Hubungkan lewat Pengaturan.")
            return
        }
        if (printing) {
            showAlertHome("Printer sedang memproses…")
            return
        }

        val nomorAntrian = antrianBerikutnya
        val nomorStr = String.format("%03d", nomorAntrian)

        showLoadingCari(true)
        val client = api()
        client.get("registrasi/api/antrian-baru/") { ok, json ->
            showLoadingCari(false)
            if (!ok || json == null) {
                showAlertHome("Gagal mengambil nomor antrian\n" + pesanGagal(client, json))
                return@get
            }
            when (json.optString("status")) {
                "ok" -> {
                    val nomor = json.optString("nomor", nomorStr)
                    antrianBerikutnya = json.optInt("berikutnya", nomorAntrian + 1)
                    updateAntrianDisplay()
                    if (activeTransport() != null && !printing) {
                        cetakAntrianFromHome(nomor)
                    } else {
                        showAlertHome(getString(R.string.msg_antrian_tidak_cetak, nomor))
                    }
                }
                else -> showAlertHome(
                    json.optString("pesan", "Gagal mengambil nomor antrian")
                )
            }
        }
    }

    /**
     * Pesan penyebab kegagalan request: pakai "pesan" dari server bila ada,
     * kalau tidak pakai detail error dari [ApiClient] (HTTP code / exception).
     */
    private fun pesanGagal(client: ApiClient, json: JSONObject?): String {
        json?.optString("pesan")?.takeIf { it.isNotBlank() }?.let { return it }
        return client.lastError.ifBlank { "Tidak ada respons dari server" }
    }

    private fun cetakAntrianFromHome(nomor: String) {
        val transport = activeTransport()
        if (transport == null) {
            showAlertHome("Printer belum terhubung. Hubungkan lewat Pengaturan.")
            return
        }
        if (printing) {
            showAlertHome("Printer sedang memproses…")
            return
        }
        printing = true

        val width = Prefs.paperWidth(this)
        val dash = "-".repeat(width)
        val now = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val ticket = JSONObject()
            .put("width", width)
            .put("title", "ANJUNGAN MANDIRI")
            .put("subtitle", "PENDAFTARAN PASIEN BARU")
            .put(
                "lines", JSONArray()
                    .put(JSONObject().put("text", dash).put("align", "c"))
                    .put(JSONObject().put("text", "No. Antrian: $nomor").put("align", "c").put("bold", true))
                    .put(JSONObject().put("text", dash).put("align", "c"))
                    .put(JSONObject().put("text", "Pasien Baru").put("align", "c"))
                    .put(JSONObject().put("text", dash).put("align", "c"))
                    .put(JSONObject().put("text", now).put("align", "c"))
                    .put(JSONObject().put("text", dash).put("align", "c"))
            )
            .put("footer", "AnjunganMandiri")
            .put("feed", 3)
            .put("cut", true)

        runPrint(TicketBuilder.build(ticket)) { ok ->
            printing = false
            if (!ok) {
                showAlertHome("Gagal mencetak. Cek printer di Pengaturan.")
            }
        }
    }

    private fun showAlertHome(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun hideKeyboardHome() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val view = binding.screenHome.root.findViewById<EditText>(R.id.etIdentitasKontrol)
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // ---------- Helper UI ----------

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.screenCari.etCari.windowToken, 0)
    }

    private fun showKeyboard() {
        binding.screenCari.etCari.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.screenCari.etCari, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private fun alert(bgRes: Int, fgColor: Int, text: String): TextView {
        val tv = TextView(this)
        tv.setBackgroundResource(bgRes)
        tv.setTextColor(color(fgColor))
        tv.text = text
        tv.textSize = 13f
        val pad = dp(12)
        tv.setPadding(pad, pad, pad, pad)
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        return tv
    }

    private fun infoRow(label: String, value: String, boldValue: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(
            TextView(this).apply {
                text = label
                setTextColor(color(R.color.muted))
                textSize = 12f
                setTypeface(Typeface.DEFAULT_BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
        )
        row.addView(
            TextView(this).apply {
                text = ":"
                setTextColor(color(R.color.muted))
                textSize = 12f
            }
        )
        row.addView(
            TextView(this).apply {
                text = value.ifBlank { "-" }
                setTextColor(color(R.color.ink))
                textSize = 12f
                if (boldValue) setTypeface(Typeface.DEFAULT_BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        return row
    }

    private fun chip(text: String, fgColor: Int, clickable: Boolean = false): TextView {
        val tv = TextView(this)
        tv.setBackgroundResource(R.drawable.bg_badge)
        tv.setTextColor(color(fgColor))
        tv.text = text
        tv.textSize = 11f
        val pad = dp(6)
        tv.setPadding(pad, dp(2), pad, dp(2))
        tv.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(4); topMargin = dp(2) }
        if (clickable) {
            tv.setPadding(dp(10), dp(4), dp(10), dp(4))
            tv.isClickable = true
        }
        return tv
    }

    private fun primaryButton(text: String): Button {
        val b = Button(this)
        b.setBackgroundResource(R.drawable.bg_btn_primary)
        b.text = text
        b.setTextColor(Color.WHITE)
        b.isAllCaps = false
        b.setTypeface(b.typeface, android.graphics.Typeface.BOLD)
        return b
    }

    private fun outlineButton(text: String): Button {
        val b = Button(this)
        b.setBackgroundResource(R.drawable.bg_btn_outline)
        b.text = text
        b.setTextColor(color(R.color.muted))
        b.isAllCaps = false
        return b
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
