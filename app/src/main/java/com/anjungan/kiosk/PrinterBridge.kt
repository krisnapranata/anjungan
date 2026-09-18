package com.anjungan.kiosk

import android.util.Base64
import android.webkit.JavascriptInterface
import com.anjungan.printcore.BluetoothPrinterManager
import com.anjungan.printcore.EscPos
import com.anjungan.printcore.PrinterTransport
import com.anjungan.printcore.TicketBuilder
import com.anjungan.printcore.UsbPrinterManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class PrinterBridge(
    private val usb: UsbPrinterManager,
    private val bluetooth: BluetoothPrinterManager
) {

    private val active: PrinterTransport?
        get() = when {
            usb.isConnected() -> usb
            bluetooth.isConnected() -> bluetooth
            else -> null
        }

    private fun send(bytes: ByteArray): Boolean {
        val t = active ?: return false
        return when (t) {
            is BluetoothPrinterManager -> sendEnsured { cb -> t.printEnsured(bytes) { cb(it) } }
            is UsbPrinterManager -> sendEnsured { cb -> t.printEnsured(bytes) { cb(it) } }
            else -> t.print(bytes)
        }
    }

    private fun sendEnsured(exec: ((Boolean) -> Unit) -> Unit): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        exec {
            ok = it
            latch.countDown()
        }
        return try {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS) && ok
        } catch (e: InterruptedException) {
            false
        }
    }

    @JavascriptInterface
    fun print(text: String): Boolean {
        if (text.isEmpty()) return false
        val out = ByteArrayOutputStream()
        out.write(EscPos.init())
        text.split('\n').forEach { line ->
            out.write(EscPos.encode(line))
            out.write('\n'.code)
        }
        out.write(EscPos.feed(2))
        return send(out.toByteArray())
    }

    @JavascriptInterface
    fun printTicket(json: String): Boolean {
        return try {
            val bytes = TicketBuilder.build(JSONObject(json))
            send(bytes)
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun printRaw(base64: String): Boolean {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            bytes.isNotEmpty() && send(bytes)
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun cut(): Boolean {
        val out = ByteArrayOutputStream()
        out.write(EscPos.feed(6))
        out.write(EscPos.cut())
        return send(out.toByteArray())
    }

    @JavascriptInterface
    fun status(): String {
        val transport: PrinterTransport? = active
        val status = when {
            transport != null -> transport.status()
            usb.status() == "not_found" -> bluetooth.status()
            else -> usb.status()
        }
        return JSONObject()
            .put("status", status)
            .put("connected", transport != null)
            .put("transport", if (transport === usb) "usb" else if (transport === bluetooth) "bluetooth" else "none")
            .put("device", if (transport === bluetooth) {
                bluetooth.connectedName ?: bluetooth.connectedAddress ?: "Caysn IW-J82BT"
            } else {
                "Caysn IW-J82BT"
            })
            .toString()
    }
}
