package com.anjungan.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class KtpData(
    val nik: String,
    val nama: String,
    val tmpLahir: String,
    val tglLahir: String,
    val jk: String,
    val golDarah: String,
    val alamat: String,
    val rtRw: String,
    val kelDesa: String,
    val kecamatan: String,
    val agama: String,
    val sttsNikah: String,
    val pekerjaan: String,
    val kewarganegaraan: String,
    val provinsi: String,
    val kabKota: String
) : java.io.Serializable

class KtpOcr {

    private val recognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun readNik(context: Context, uri: Uri, cb: (List<String>) -> Unit) {
        executor.execute {
            val niks = try {
                val image = loadImage(context, uri)
                val text = Tasks.await(recognizer.process(image)).text
                extractNiks(text)
            } catch (e: Exception) {
                emptyList()
            }
            main.post { cb(niks) }
        }
    }

    fun readKtp(context: Context, uri: Uri, cb: (KtpData?) -> Unit) {
        executor.execute {
            val ktp = try {
                val image = loadImage(context, uri)
                val text = Tasks.await(recognizer.process(image)).text
                extractKtpData(text)
            } catch (e: Exception) {
                null
            }
            main.post { cb(ktp) }
        }
    }

    private fun loadImage(context: Context, uri: Uri): InputImage {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        var sample = 1
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w > 0 && h > 0) {
            while ((w / sample) > MAX_DIM || (h / sample) > MAX_DIM) sample *= 2
        }

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalStateException("decode failed")

        val rotation = readRotation(bytes)
        val rotated = if (rotation != 0f) rotate(bitmap, rotation) else bitmap
        return InputImage.fromBitmap(rotated, 0)
    }

    private fun readRotation(bytes: ByteArray): Float {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            when (exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) bitmap.recycle()
        return rotated
    }

    companion object {
        private const val MAX_DIM = 1600

        fun extractNiks(text: String): List<String> {
            val candidates = LinkedHashSet<String>()

            Regex("\\d{16}").findAll(text).forEach { candidates.add(it.value) }

            val stripped = text.replace(Regex("[^0-9]"), "")
            Regex("\\d{16}").find(stripped)?.value?.let { candidates.add(it) }

            Regex("[0-9OolIZzsSBG]+").findAll(text).forEach { m ->
                val seg = m.value
                if (seg.length >= 16) {
                    for (start in 0..seg.length - 16) {
                        candidates.add(normalizeDigits(seg.substring(start, start + 16)))
                    }
                }
            }

            return candidates.toList().sortedByDescending { isPlausibleNik(it) }
        }

        fun isPlausibleNik(nik: String): Boolean {
            if (nik.length != 16 || !nik.all { it.isDigit() }) return false
            val dd = nik.substring(6, 8).toIntOrNull() ?: return false
            val mm = nik.substring(8, 10).toIntOrNull() ?: return false
            return dd in 1..71 && mm in 1..12
        }

        private fun normalizeDigits(text: String): String = buildString(text.length) {
            for (c in text) {
                append(
                    when (c) {
                        'O', 'o' -> '0'
                        'I', 'l' -> '1'
                        'Z', 'z' -> '2'
                        'S', 's' -> '5'
                        'G' -> '6'
                        'B' -> '8'
                        else -> c
                    }
                )
            }
        }

        fun extractKtpData(text: String): KtpData? {
            val niks = extractNiks(text)
            val nik = niks.firstOrNull() ?: return null

            val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

            val labelMap = linkedMapOf(
                "NIK" to "nik",
                "NAMA" to "nama",
                "TEMPAT/TGL LAHIR" to "tmpTglLahir",
                "TEMPAT TGL LAHIR" to "tmpTglLahir",
                "JENIS KELAMIN" to "jk",
                "GOL. DARAH" to "golDarah",
                "GOL DARAH" to "golDarah",
                "ALAMAT" to "alamat",
                "RT/RW" to "rtRw",
                "RT RW" to "rtRw",
                "KELURAHAN/DESA" to "kelDesa",
                "KELURAHAN DESA" to "kelDesa",
                "KEL/DESA" to "kelDesa",
                "KECAMATAN" to "kecamatan",
                "AGAMA" to "agama",
                "STATUS PERKAWINAN" to "sttsNikah",
                "PEKERJAAN" to "pekerjaan",
                "KEWARGANEGARAAN" to "kewarganegaraan",
                "PROVINSI" to "provinsi",
                "KABUPATEN/KOTA" to "kabKota",
                "KABUPATEN KOTA" to "kabKota",
                "KAB/KOTA" to "kabKota"
            )

            val extracted = mutableMapOf<String, String>()
            var pendingKey: String? = null

            for (line in lines) {
                val upper = line.uppercase()
                val match = matchLabel(upper, labelMap)

                if (match != null && extracted[match.key].isNullOrEmpty()) {
                    val value = valueAfterLabel(line, match.label)
                    if (value.isNotEmpty()) {
                        extracted[match.key] = value
                        pendingKey = null
                    } else {
                        pendingKey = match.key
                    }
                    continue
                }

                if (pendingKey != null && extracted[pendingKey].isNullOrEmpty()) {
                    if (!isLabelLine(upper, labelMap)) {
                        extracted[pendingKey!!] = line
                        pendingKey = null
                    }
                }
            }

            val tmpTglLahir = extracted["tmpTglLahir"] ?: ""
            val parts = tmpTglLahir.split(",").map { it.trim() }
            val tmpLahir = parts.getOrElse(0) { "" }
            val tglLahir = if (parts.size >= 2) parseTglLahir(parts[1]) else parseTglLahir(parts[0])

            val jkRaw = extracted["jk"]?.uppercase() ?: ""
            val jk = when {
                jkRaw.contains("LAKI") -> "L"
                jkRaw.contains("PEREMPUAN") -> "P"
                else -> ""
            }

            val sttsNikahRaw = extracted["sttsNikah"]?.uppercase() ?: ""
            val sttsNikah = when {
                sttsNikahRaw.contains("BELUM") -> "BELUM MENIKAH"
                sttsNikahRaw.contains("KAWIN") && !sttsNikahRaw.contains("BELUM") -> "MENIKAH"
                sttsNikahRaw.contains("JANDA") -> "JANDA"
                sttsNikahRaw.contains("DUDHA") -> "DUDHA"
                sttsNikahRaw.contains("JOMBLO") -> "JOMBLO"
                else -> ""
            }

            return KtpData(
                nik = nik,
                nama = extracted["nama"] ?: "",
                tmpLahir = tmpLahir,
                tglLahir = tglLahir,
                jk = jk,
                golDarah = extracted["golDarah"] ?: "",
                alamat = extracted["alamat"] ?: "",
                rtRw = extracted["rtRw"] ?: "",
                kelDesa = extracted["kelDesa"] ?: "",
                kecamatan = extracted["kecamatan"] ?: "",
                agama = extracted["agama"] ?: "",
                sttsNikah = sttsNikah,
                pekerjaan = extracted["pekerjaan"] ?: "",
                kewarganegaraan = extracted["kewarganegaraan"] ?: "",
                provinsi = extracted["provinsi"] ?: "",
                kabKota = extracted["kabKota"] ?: ""
            )
        }

        private data class LabelMatch(val key: String, val label: String)

        private fun matchLabel(upper: String, labelMap: Map<String, String>): LabelMatch? {
            for ((label, key) in labelMap) {
                if (upper == label || upper.startsWith("$label:") || upper.startsWith("$label ")) {
                    return LabelMatch(key, label)
                }
            }
            return null
        }

        private fun isLabelLine(upper: String, labelMap: Map<String, String>): Boolean =
            labelMap.keys.any { upper == it || upper.startsWith("$it:") || upper.startsWith("$it ") }

        private fun valueAfterLabel(line: String, label: String): String {
            var idx = line.indexOf(label, ignoreCase = true)
            if (idx < 0) return ""
            idx += label.length
            return line.substring(idx).trimStart(':', ' ', '\t')
        }

        private fun parseTglLahir(tgl: String): String {
            val cleaned = tgl.replace(Regex("[^0-9]"), "")
            if (cleaned.length == 8) {
                val dd = cleaned.substring(0, 2)
                val mm = cleaned.substring(2, 4)
                val yyyy = cleaned.substring(4, 8)
                return "$yyyy-$mm-$dd"
            }
            return ""
        }
    }
}
