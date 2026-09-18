package com.anjungan.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.anjungan.kiosk.databinding.ActivityAdminBinding
import com.anjungan.printcore.BluetoothPrinterManager
import com.anjungan.printcore.UsbPrinterManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var printer: UsbPrinterManager
    private lateinit var bluetoothPrinter: BluetoothPrinterManager
    private lateinit var bridge: PrinterBridge

    private val bondedAddresses = mutableListOf<String>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbPrinterManager.ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(
                UsbManager.EXTRA_PERMISSION_GRANTED, false
            )
            if (granted) {
                val device = intent.usbDevice()
                if (printer.connect(device)) {
                    updatePrinterStatus()
                    Toast.makeText(
                        context, "Printer USB terhubung", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private val btPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) loadBondedDevices() else Toast.makeText(
                this, "Izin Bluetooth ditolak", Toast.LENGTH_LONG
            ).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        printer = UsbPrinterManager(this)
        bluetoothPrinter = BluetoothPrinterManager(this)
        bridge = PrinterBridge(printer, bluetoothPrinter)

        binding.etUrl.setText(SettingsStore.serverUrl(this))

        binding.btnSave.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            SettingsStore.setServerUrl(this, url)
            Toast.makeText(this, "Tersimpan", Toast.LENGTH_SHORT).show()
        }

        binding.btnTest.setOnClickListener { testPrint() }
        binding.btnConnect.setOnClickListener { connectUsb() }
        binding.btnDisconnect.setOnClickListener {
            printer.disconnect()
            bluetoothPrinter.disconnect()
            updatePrinterStatus()
        }
        binding.btnBack.setOnClickListener { finish() }

        binding.btnConnectBt.setOnClickListener { connectBluetooth() }
        binding.btnRefreshBt.setOnClickListener { loadBondedDevices() }
        binding.spBonded.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (position in bondedAddresses.indices) {
                    SettingsStore.setPrinterMac(this@AdminActivity, bondedAddresses[position])
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val filter = IntentFilter(UsbPrinterManager.ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !bluetoothPrinter.hasConnectPermission()
        ) {
            btPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            loadBondedDevices()
        }

        updatePrinterStatus()
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun connectUsb() {
        val dev = printer.findPrinter()
        if (dev == null) {
            Toast.makeText(this, "Printer USB tidak ditemukan", Toast.LENGTH_LONG).show()
            return
        }
        if (printer.hasPermission(dev)) {
            if (printer.connect(dev)) {
                Toast.makeText(this, "Printer USB terhubung", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Gagal membuka koneksi printer", Toast.LENGTH_LONG).show()
            }
        } else {
            printer.requestPermission(dev)
            Toast.makeText(this, "Izinkan akses USB pada dialog", Toast.LENGTH_LONG).show()
        }
        updatePrinterStatus()
    }

    private fun connectBluetooth() {
        if (!bluetoothPrinter.isBluetoothEnabled()) {
            Toast.makeText(this, "Bluetooth mati. Nyalakan dulu.", Toast.LENGTH_LONG).show()
            return
        }
        if (!bluetoothPrinter.hasConnectPermission()) {
            btPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val pos = binding.spBonded.selectedItemPosition
        if (pos !in bondedAddresses.indices) {
            Toast.makeText(this, "Pilih printer dulu (atau pair di pengaturan Android)", Toast.LENGTH_LONG).show()
            loadBondedDevices()
            return
        }
        val address = bondedAddresses[pos]
        binding.btnConnectBt.isEnabled = false
        bluetoothPrinter.connect(address) { ok ->
            runOnUiThread {
                binding.btnConnectBt.isEnabled = true
                updatePrinterStatus()
                Toast.makeText(
                    this,
                    if (ok) "Printer Bluetooth terhubung" else "Gagal konek Bluetooth",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun loadBondedDevices() {
        bondedAddresses.clear()
        val devices = bluetoothPrinter.bondedDevices()
        val savedMac = SettingsStore.printerMac(this)
        devices.forEach { bondedAddresses.add(it.address) }
        val names = devices.map {
            "${it.name ?: "Tanpa nama"} (${it.address})"
        }
        if (names.isEmpty()) {
            binding.spBonded.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                listOf("Tidak ada perangkat terpasang (pair dulu)")
            )
        } else {
            val adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item, names
            )
            binding.spBonded.adapter = adapter
            val index = bondedAddresses.indexOf(savedMac)
            if (index >= 0) binding.spBonded.setSelection(index)
        }
        updatePrinterStatus()
    }

    private fun testPrint() {
        if (!printer.isConnected() && !bluetoothPrinter.isConnected()) {
            Toast.makeText(
                this, "Printer belum terhubung. Hubungkan USB atau Bluetooth dulu.", Toast.LENGTH_LONG
            ).show()
            updatePrinterStatus()
            return
        }
        val now = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val ticket = """
            {
              "width": 48,
              "title": "ANJUNGAN KIOSK",
              "subtitle": "TEST PRINT",
              "lines": [
                {"text": "--------------------------------", "align": "c"},
                {"text": "Printer Caysn IW-J82BT", "align": "c"},
                {"text": "Status: OK", "align": "c", "bold": true},
                {"text": "--------------------------------", "align": "c"},
                {"text": "$now", "align": "c"}
              ],
              "footer": "SIMRS - RS",
              "feed": 3,
              "cut": true
            }
        """.trimIndent()
        val ok = bridge.printTicket(ticket)
        Toast.makeText(
            this,
            if (ok) "Cetak test berhasil" else "Cetak test GAGAL",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updatePrinterStatus() {
        val dev = printer.findPrinter()
        binding.tvPrinterStatus.text = when (printer.status()) {
            "connected" -> "USB: TERHUBUNG"
            "not_found" -> "USB: TIDAK DITEMUKAN"
            "permission_denied" -> "USB: ditemukan, izin belum diberikan"
            else -> "USB: terputus"
        } + if (dev != null) " [${dev.deviceName}]" else ""

        binding.tvBtStatus.text = when (bluetoothPrinter.status()) {
            "connected" -> "Bluetooth: TERHUBUNG" +
                (bluetoothPrinter.connectedName?.let { " [$it]" } ?: "")
            "bluetooth_off" -> "Bluetooth: mati di perangkat"
            "permission_denied" -> "Bluetooth: izin belum diberikan"
            else -> "Bluetooth: terputus"
        }
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
