package com.anjungan.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.anjungan.kiosk.databinding.ActivityAdminBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var printer: UsbPrinterManager
    private lateinit var bridge: PrinterBridge

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
                        context, "Printer terhubung", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        printer = UsbPrinterManager(this)
        bridge = PrinterBridge(printer)

        binding.etUrl.setText(SettingsStore.serverUrl(this))

        binding.btnSave.setOnClickListener {
            val url = binding.etUrl.text.toString().trim()
            SettingsStore.setServerUrl(this, url)
            Toast.makeText(this, "Tersimpan", Toast.LENGTH_SHORT).show()
        }

        binding.btnTest.setOnClickListener { testPrint() }
        binding.btnConnect.setOnClickListener { connectPrinter() }
        binding.btnDisconnect.setOnClickListener {
            printer.disconnect()
            updatePrinterStatus()
        }
        binding.btnBack.setOnClickListener { finish() }

        val filter = IntentFilter(UsbPrinterManager.ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        updatePrinterStatus()
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun connectPrinter() {
        val dev = printer.findPrinter()
        if (dev == null) {
            Toast.makeText(this, "Printer tidak ditemukan (VID/PID tidak cocok)", Toast.LENGTH_LONG).show()
            return
        }
        if (printer.hasPermission(dev)) {
            if (printer.connect(dev)) {
                Toast.makeText(this, "Printer terhubung", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Gagal membuka koneksi printer", Toast.LENGTH_LONG).show()
            }
        } else {
            printer.requestPermission(dev)
            Toast.makeText(this, "Izinkan akses USB pada dialog", Toast.LENGTH_LONG).show()
        }
        updatePrinterStatus()
    }

    private fun testPrint() {
        if (!printer.isConnected() && !printer.connect(null)) {
            Toast.makeText(
                this, "Printer belum terhubung. Tekan Hubungkan dulu.", Toast.LENGTH_LONG
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
            "connected" -> "Printer: TERHUBUNG"
            "not_found" -> "Printer: TIDAK DITEMUKAN (cari VID 19275 / PID 14384)"
            "permission_denied" -> "Printer: ditemukan, izin USB belum diberikan"
            else -> "Printer: terputus"
        } + if (dev != null) " [${dev.deviceName}]" else ""
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
