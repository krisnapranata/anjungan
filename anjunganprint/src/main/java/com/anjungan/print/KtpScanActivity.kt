package com.anjungan.print

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.anjungan.print.databinding.ActivityKtpScanBinding
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class KtpScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NIK = "nik"
        const val EXTRA_KTP_DATA = "ktp_data"
    }

    private lateinit var binding: ActivityKtpScanBinding
    private lateinit var recognizer: com.google.mlkit.vision.text.TextRecognizer

    private val executor = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var currentNik: String? = null
    private var currentKtpData: KtpData? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, "Izin kamera ditolak", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKtpScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableFullScreen()

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        binding.btnClose.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        binding.btnCapture.setOnClickListener {
            val nik = currentNik
            if (nik == null) {
                Toast.makeText(this, "NIK belum terdeteksi", Toast.LENGTH_SHORT).show()
            } else {
                val data = Intent().apply {
                    putExtra(EXTRA_NIK, nik)
                    currentKtpData?.let { putExtra(EXTRA_KTP_DATA, it) }
                }
                setResult(RESULT_OK, data)
                finish()
            }
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindUseCases()
            } catch (_: Exception) {
                binding.tvHint.text = "Kamera tidak tersedia"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor, ::analyze)

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        } catch (_: Exception) {
            binding.tvHint.text = "Gagal membuka kamera"
        }
    }

    private fun analyze(imageProxy: ImageProxy) {
        if (analyzing.getAndSet(true)) {
            imageProxy.close()
            return
        }
        try {
            val media = imageProxy.image
            if (media != null) {
                val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
                val text = Tasks.await(recognizer.process(image)).text
                val nik = KtpOcr.extractNiks(text).firstOrNull()
                if (nik != null && KtpOcr.isPlausibleNik(nik)) {
                    val ktpData = KtpOcr.extractKtpData(text)
                    runOnUiThread { updateNik(nik, ktpData) }
                }
            }
        } catch (_: Exception) {
        } finally {
            analyzing.set(false)
            imageProxy.close()
        }
    }

    private fun updateNik(nik: String, ktpData: KtpData?) {
        if (nik == currentNik) return
        currentNik = nik
        currentKtpData = ktpData
        binding.tvNik.text = nik
        binding.btnCapture.isEnabled = true
    }

    /** Sama seperti MainActivity: layar penuh tanpa status/navigation bar. */
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
}
