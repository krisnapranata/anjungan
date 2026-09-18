package com.anjungan.print

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ApiClient(private val baseUrl: String) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun get(path: String, cb: (ok: Boolean, json: JSONObject?) -> Unit) {
        executor.execute {
            val r = request(path) { it.requestMethod = "GET" }
            main.post { cb(r.first, r.second) }
        }
    }

    fun postForm(path: String, params: Map<String, String>, cb: (ok: Boolean, json: JSONObject?) -> Unit) {
        executor.execute {
            val body = params.entries.joinToString("&") { (k, v) ->
                "${enc(k)}=${enc(v)}"
            }
            val r = request(path) { conn ->
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            main.post { cb(r.first, r.second) }
        }
    }

    fun postMultipart(
        path: String,
        fieldName: String,
        bytes: ByteArray,
        fileName: String,
        cb: (ok: Boolean, json: JSONObject?) -> Unit
    ) {
        executor.execute {
            val boundary = "----AnjunganBoundary${UUID.randomUUID()}"
            val r = request(path) { conn ->
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                val out: OutputStream = conn.outputStream
                out.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                out.write(
                    "Content-Disposition: form-data; name=\"$fieldName\"; filename=\"$fileName\"\r\n"
                        .toByteArray(Charsets.UTF_8)
                )
                out.write("Content-Type: image/jpeg\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(bytes)
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
            main.post { cb(r.first, r.second) }
        }
    }

    private fun request(
        path: String,
        configure: (HttpURLConnection) -> Unit
    ): Pair<Boolean, JSONObject?> {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(baseUrl + path)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            configure(conn)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
            val json = try {
                JSONObject(body)
            } catch (e: Exception) {
                null
            }
            return (code in 200..299) to json
        } catch (e: Exception) {
            return false to null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8")
}
