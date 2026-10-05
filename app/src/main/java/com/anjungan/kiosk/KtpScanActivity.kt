package com.anjungan.kiosk

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.anjungan.kiosk.databinding.ActivityKtpScanBinding
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class KtpScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NIK = "nik"
    }

    private lateinit var binding: ActivityKtpScanBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var recognizer: com.google.mlkit.vision.text.TextRecognizer

    private val executor = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityKtpScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        binding.btnClose.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        binding.btnCapture.setOnClickListener { capturePhoto() }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
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
                binding.tvStatus.text = "Kamera tidak tersedia"
                binding.tvStatus.visibility = View.VISIBLE
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

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
                imageCapture
            )
        } catch (_: Exception) {
            binding.tvStatus.text = "Gagal membuka kamera"
            binding.tvStatus.visibility = View.VISIBLE
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
                val result = Tasks.await(recognizer.process(image))
                val nik = extractNik(result.text)
                if (nik != null) {
                    runOnUiThread { onNikFound(nik) }
                    return
                }
            }
        } catch (_: Exception) {
        } finally {
            analyzing.set(false)
            imageProxy.close()
        }
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: return
        val file = File(cacheDir, "ktp_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    try {
                        val image = InputImage.fromFilePath(this@KtpScanActivity, Uri.fromFile(file))
                        val result = Tasks.await(recognizer.process(image))
                        val nik = extractNik(result.text)
                        file.delete()
                        runOnUiThread {
                            if (nik != null) {
                                onNikFound(nik)
                            } else {
                                binding.tvStatus.text = "NIK tidak terbaca. Coba lagi."
                                binding.tvStatus.visibility = View.VISIBLE
                            }
                        }
                    } catch (_: Exception) {
                        file.delete()
                        runOnUiThread {
                            binding.tvStatus.text = "NIK tidak terbaca. Coba lagi."
                            binding.tvStatus.visibility = View.VISIBLE
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    file.delete()
                    runOnUiThread {
                        binding.tvStatus.text = "Gagal mengambil foto"
                        binding.tvStatus.visibility = View.VISIBLE
                    }
                }
            }
        )
    }

    private fun onNikFound(nik: String) {
        val data = Intent().putExtra(EXTRA_NIK, nik)
        setResult(RESULT_OK, data)
        finish()
    }

    private fun extractNik(text: String): String? {
        val candidates = Regex("\\d{16}").findAll(text).map { it.value }
        candidates.forEach { if (isPlausibleNik(it)) return it }

        val stripped = text.replace(Regex("[^0-9]"), "")
        return Regex("\\d{16}").find(stripped)?.value?.takeIf { isPlausibleNik(it) }
    }

    private fun isPlausibleNik(nik: String): Boolean {
        if (nik.length != 16 || !nik.all { it.isDigit() }) return false
        val dd = nik.substring(6, 8).toIntOrNull() ?: return false
        val mm = nik.substring(8, 10).toIntOrNull() ?: return false
        return dd in 1..71 && mm in 1..12
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
}
