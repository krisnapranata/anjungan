package com.anjungan.printcore

interface PrinterTransport {
    fun print(bytes: ByteArray): Boolean
    fun disconnect()
    fun isConnected(): Boolean
    fun status(): String
}
