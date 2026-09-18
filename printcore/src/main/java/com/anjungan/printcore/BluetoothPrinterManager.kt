package com.anjungan.printcore

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class BluetoothPrinterManager(private val context: Context) : PrinterTransport {

    companion object {
        private const val TAG = "BtPrinter"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val RECONNECT_DELAY_MS = 800L
        private const val PRE_WRITE_DELAY_MS = 500L
        private const val LARGE_JOB_THRESHOLD = 4096
        private const val WRITE_CHUNK = 2048
        private const val WRITE_PAUSE_MS = 80L
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var connected = false
    @Volatile
    private var connecting = false
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    var connectedName: String? = null
        private set
    var connectedAddress: String? = null
        private set

    var lastAddress: String? = null
        private set

    @Volatile
    var lastDisconnectAt: Long = 0
        private set

    private val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.BLUETOOTH_CONNECT
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasConnectPermission()) {
            return emptyList()
        }
        val bonded = adapter?.bondedDevices ?: emptySet()
        return bonded.sortedBy { it.name ?: it.address }
    }

    fun connect(address: String, callback: ((Boolean) -> Unit)? = null) {
        if (connected) {
            callback?.invoke(true)
            return
        }
        if (connecting) {
            callback?.let { cb ->
                synchronized(pendingCallbacks) { pendingCallbacks.add(cb) }
            }
            return
        }
        executor.execute {
            connecting = true
            val ok = doConnect(address)
            connecting = false
            val cbs = synchronized(pendingCallbacks) {
                pendingCallbacks.toList().also { pendingCallbacks.clear() }
            }
            main.post {
                callback?.invoke(ok)
                cbs.forEach { it.invoke(ok) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun doConnect(address: String): Boolean {
        val btAdapter = adapter ?: return false
        if (!btAdapter.isEnabled) return false
        try {
            disconnect()
            val device: BluetoothDevice = btAdapter.getRemoteDevice(address)
            var sock: BluetoothSocket? = null
            try {
                sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                sock.connect()
            } catch (e: IOException) {
                try { sock?.close() } catch (_: Exception) {}
                Log.w(TAG, "SPP connect gagal, coba insecure socket", e)
                sock = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                sock.connect()
            }
            socket = sock
            output = sock.outputStream
            connected = true
            connectedName = try { device.name } catch (e: Exception) { null }
            connectedAddress = address
            lastAddress = address
            Log.i(TAG, "Bluetooth terhubung ke $connectedName ($address)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Gagal konek bluetooth", e)
            disconnect()
            return false
        }
    }

    override fun disconnect() {
        try { output?.flush() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        output = null
        socket = null
        connected = false
        connecting = false
        lastDisconnectAt = System.currentTimeMillis()
    }

    override fun isConnected(): Boolean = connected

    @Synchronized
    override fun print(bytes: ByteArray): Boolean = tryPrint(bytes)

    /**
     * Cetak dengan koneksi Bluetooth yang SELALU baru untuk setiap job.
     * Printer Caysn IW-J82BT macet di tengah job jika job berikutnya dikirim
     * lewat koneksi yang sama (terutama setelah perintah cut). Membuka
     * koneksi baru per job membuat printer mereset state inputnya.
     * Callback dipanggil di thread utama.
     */
    fun printEnsured(bytes: ByteArray, preDelayMs: Long = 0, cb: (Boolean) -> Unit) {
        executor.execute {
            if (preDelayMs > 0) {
                try {
                    Thread.sleep(preDelayMs)
                } catch (_: InterruptedException) {
                }
            }
            val addr = lastAddress
            var ok = false
            if (addr == null) {
                ok = tryPrint(bytes)
            } else {
                var attempt = 0
                while (!ok && attempt < 2) {
                    attempt++
                    if (doConnect(addr)) {
                        try {
                            Thread.sleep(PRE_WRITE_DELAY_MS)
                        } catch (_: InterruptedException) {
                        }
                        ok = tryPrint(bytes)
                    } else {
                        try {
                            Thread.sleep(RECONNECT_DELAY_MS)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
            }
            main.post { cb(ok) }
        }
    }

    @Synchronized
    private fun tryPrint(bytes: ByteArray): Boolean {
        val out = output ?: return false
        if (!connected) return false
        return try {
            var offset = 0
            while (offset < bytes.size) {
                val chunkSize = if (bytes.size >= LARGE_JOB_THRESHOLD) WRITE_CHUNK else bytes.size
                val end = minOf(offset + chunkSize, bytes.size)
                out.write(bytes, offset, end - offset)
                offset = end
                if (offset < bytes.size) {
                    try {
                        Thread.sleep(WRITE_PAUSE_MS)
                    } catch (_: InterruptedException) {
                    }
                }
            }
            out.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Tulis bluetooth gagal", e)
            disconnect()
            false
        }
    }

    override fun status(): String = when {
        connected -> "connected"
        !isBluetoothEnabled() -> "bluetooth_off"
        !hasConnectPermission() -> "permission_denied"
        else -> "disconnected"
    }
}
