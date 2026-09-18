package com.anjungan.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.anjungan.kiosk.databinding.ActivityMainBinding
import com.anjungan.printcore.BluetoothPrinterManager
import com.anjungan.printcore.UsbPrinterManager
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val SCHEME_ADMIN = "anjungan://admin"
        private const val TAPS_FOR_ADMIN = 7
        private const val TAP_WINDOW_MS = 700L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var printer: UsbPrinterManager
    private lateinit var bluetoothPrinter: BluetoothPrinterManager
    private lateinit var bridge: PrinterBridge

    private var lastLoadedUrl: String? = null
    private var tapCount = 0
    private var lastTapTime = 0L
    private var pendingCameraUri: Uri? = null
    private var pendingFileChooser: ValueCallback<Array<Uri>>? = null
    private var btPermissionAsked = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDevice()
                    if (device != null && printer.connect(device)) {
                        injectPrinterStatus()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    printer.disconnect()
                    injectPrinterStatus()
                }
                UsbPrinterManager.ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    if (granted) {
                        val device = intent.usbDevice()
                        if (printer.connect(device)) injectPrinterStatus()
                    }
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (bluetoothPrinter.isConnected() &&
                        System.currentTimeMillis() - bluetoothPrinter.lastDisconnectAt > 2000
                    ) {
                        bluetoothPrinter.disconnect()
                        injectPrinterStatus()
                    }
                }
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(
                            android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1
                        ) == android.bluetooth.BluetoothAdapter.STATE_ON
                    ) {
                        autoConnectBluetooth()
                    }
                }
            }
        }
    }

    private val btPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            autoConnectBluetooth()
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileChooser
            pendingFileChooser = null
            val dataUri = result.data?.data
            val cameraUri = pendingCameraUri
            pendingCameraUri = null
            if (callback == null) return@registerForActivityResult
            when {
                dataUri != null -> callback.onReceiveValue(arrayOf(dataUri))
                cameraUri != null && cameraUri.path != null -> {
                    val file = File(cameraUri.path!!)
                    if (file.exists() && file.length() > 0) {
                        callback.onReceiveValue(arrayOf(cameraUri))
                    } else {
                        callback.onReceiveValue(null)
                    }
                }
                else -> callback.onReceiveValue(null)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        printer = UsbPrinterManager(this)
        bluetoothPrinter = BluetoothPrinterManager(this)
        bridge = PrinterBridge(printer, bluetoothPrinter)

        setupWebView()
        binding.btnRetry.setOnClickListener { loadKiosk() }

        registerUsbReceiver()
        autoConnectPrinter()

        if (SettingsStore.serverUrl(this).isBlank()) {
            openAdmin()
            binding.webView.loadUrl("about:blank")
        } else {
            loadKiosk()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        autoConnectBluetooth()
        val url = SettingsStore.serverUrl(this)
        if (url.isNotBlank() && lastLoadedUrl != url) {
            loadKiosk()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        printer.disconnect()
        bluetoothPrinter.disconnect()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            tapCount = if (now - lastTapTime < TAP_WINDOW_MS) tapCount + 1 else 1
            lastTapTime = now
            if (tapCount >= TAPS_FOR_ADMIN) {
                tapCount = 0
                openAdmin()
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString AnjunganKiosk/1.0"
        }
        binding.webView.addJavascriptInterface(bridge, "PrinterBridge")
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (request.url.toString().startsWith(SCHEME_ADMIN)) {
                    openAdmin()
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                lastLoadedUrl = url
                injectPrinterStatus()
            }

            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    binding.errorView.visibility = View.VISIBLE
                }
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val granted = request.resources.filter {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                        it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (granted.isNotEmpty()) {
                    request.grant(granted.toTypedArray())
                } else {
                    request.deny()
                }
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback

                val acceptTypes = fileChooserParams.acceptTypes ?: emptyArray()
                val wantsImage = acceptTypes.isEmpty() ||
                    acceptTypes.any { it.startsWith("image/") || it == "*/*" }

                val extraIntents = mutableListOf<Intent>()
                if (wantsImage) {
                    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    val uri = createImageUri()
                    pendingCameraUri = uri
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    cameraIntent.addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    extraIntents.add(cameraIntent)
                }

                val gallery = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = acceptTypes.firstOrNull() ?: "*/*"
                }
                val chooser = Intent.createChooser(gallery, "Pilih File")
                if (extraIntents.isNotEmpty()) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
                }
                fileChooserLauncher.launch(chooser)
                return true
            }
        }
    }

    private fun loadKiosk() {
        val url = SettingsStore.serverUrl(this)
        if (url.isBlank()) {
            openAdmin()
            return
        }
        binding.errorView.visibility = View.GONE
        binding.webView.loadUrl(url)
    }

    private fun autoConnectPrinter() {
        val dev = printer.findPrinter()
        if (dev == null) return
        if (printer.hasPermission(dev)) {
            printer.connect(dev)
        } else {
            printer.requestPermission(dev)
        }
    }

    private fun autoConnectBluetooth() {
        if (!bluetoothPrinter.isBluetoothEnabled()) return
        if (!bluetoothPrinter.hasConnectPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !btPermissionAsked) {
                btPermissionAsked = true
                btPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            return
        }
        val mac = SettingsStore.printerMac(this)
        if (mac.isBlank()) return
        if (bluetoothPrinter.isConnected()) return
        bluetoothPrinter.connect(mac) { ok ->
            if (ok) {
                runOnUiThread { injectPrinterStatus() }
            }
        }
    }

    private fun openAdmin() {
        startActivity(Intent(this, AdminActivity::class.java))
    }

    private fun createImageUri(): Uri {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun injectPrinterStatus() {
        val status = bridge.status()
        val js = "try{if(window.AnjunganOnPrinterChange){AnjunganOnPrinterChange($status)}}catch(e){}"
        binding.webView.evaluateJavascript(js, null)
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbPrinterManager.ACTION_USB_PERMISSION)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
}
