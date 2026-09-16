package com.anjungan.kiosk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

class UsbPrinterManager(private val context: Context) {

    companion object {
        private const val TAG = "UsbPrinter"
        const val VID = 19275
        const val PID = 14384
        const val ACTION_USB_PERMISSION = "com.anjungan.kiosk.USB_PERMISSION"
        private const val MAX_CHUNK = 512
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    var device: UsbDevice? = null
        private set
    private var connection: UsbDeviceConnection? = null
    private var outEndpoint: UsbEndpoint? = null
    private var claimedInterface: UsbInterface? = null

    fun findPrinter(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == VID && it.productId == PID
        }

    fun hasPermission(device: UsbDevice?): Boolean =
        device != null && usbManager.hasPermission(device)

    fun requestPermission(device: UsbDevice?) {
        if (device == null || hasPermission(device)) return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        usbManager.requestPermission(device, pi)
    }

    fun connect(device: UsbDevice?): Boolean {
        disconnect()
        val dev = device ?: findPrinter() ?: return false
        if (!hasPermission(dev)) return false
        val conn = usbManager.openDevice(dev) ?: return false
        try {
            var iface: UsbInterface? = null
            for (i in 0 until dev.interfaceCount) {
                val candidate = dev.getInterface(i)
                if (candidate.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                    iface = candidate
                    break
                }
            }
            val selected = iface ?: dev.getInterface(0)
            if (!conn.claimInterface(selected, true)) {
                conn.close()
                return false
            }
            var ep: UsbEndpoint? = null
            for (i in 0 until selected.endpointCount) {
                val candidate = selected.getEndpoint(i)
                if (candidate.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                    candidate.direction == UsbConstants.USB_DIR_OUT
                ) {
                    ep = candidate
                    break
                }
            }
            if (ep == null) {
                conn.releaseInterface(selected)
                conn.close()
                return false
            }
            this.device = dev
            this.connection = conn
            this.outEndpoint = ep
            this.claimedInterface = selected
            return true
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            try { conn.close() } catch (_: Exception) {}
            return false
        }
    }

    fun disconnect() {
        try {
            claimedInterface?.let { connection?.releaseInterface(it) }
        } catch (e: Exception) {
            Log.w(TAG, "releaseInterface failed", e)
        }
        try {
            connection?.close()
        } catch (_: Exception) {}
        connection = null
        outEndpoint = null
        claimedInterface = null
    }

    fun isConnected(): Boolean = connection != null

    @Synchronized
    fun print(bytes: ByteArray): Boolean {
        val conn = connection ?: return false
        val ep = outEndpoint ?: return false
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, minOf(offset + MAX_CHUNK, bytes.size))
            val written = conn.bulkTransfer(ep, chunk, chunk.size, 3000)
            if (written <= 0) {
                Log.e(TAG, "bulkTransfer failed at offset $offset")
                return false
            }
            offset += written
        }
        return true
    }

    fun status(): String {
        val dev = device
        return when {
            connection != null -> "connected"
            dev == null -> "not_found"
            !hasPermission(dev) -> "permission_denied"
            else -> "disconnected"
        }
    }
}
