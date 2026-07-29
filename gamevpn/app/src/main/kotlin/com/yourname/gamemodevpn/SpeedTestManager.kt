package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * HTTP-based download and upload speed test.
 * Uses Cloudflare's speed test endpoint (no account needed).
 */
object SpeedTestManager {

    private const val TAG = "SpeedTest"
    // Cloudflare speed test files (public, no auth)
    private const val DL_100KB = "https://speed.cloudflare.com/__down?bytes=102400"
    private const val DL_1MB   = "https://speed.cloudflare.com/__down?bytes=1048576"
    private const val DL_10MB  = "https://speed.cloudflare.com/__down?bytes=10485760"
    private const val UL_URL   = "https://speed.cloudflare.com/__up"

    data class SpeedResult(
        val downloadMbps: Float,
        val uploadMbps: Float,
        val latencyMs: Long,
        val jitterMs: Long,
        val server: String = "Cloudflare"
    )

    interface ProgressCallback {
        fun onDownloadProgress(percent: Int, currentMbps: Float)
        fun onUploadProgress(percent: Int, currentMbps: Float)
        fun onComplete(result: SpeedResult)
        fun onError(msg: String)
    }

    fun runTest(callback: ProgressCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Latency (5 pings)
                val latencies = (1..5).map { measureLatency() }
                val latencyMs = latencies.filter { it > 0 }.average().toLong().coerceAtLeast(1)
                val jitterMs = if (latencies.size > 1) {
                    latencies.zipWithNext { a, b -> Math.abs(a - b) }.average().toLong()
                } else 0L

                // 2. Download (100KB warm-up + 1MB real)
                measureDownload(DL_100KB) { _, _ -> } // warm-up
                val dlMbps = measureDownload(DL_1MB) { pct, mbps ->
                    withContext(Dispatchers.Main) { callback.onDownloadProgress(pct, mbps) }
                }

                // 3. Upload (512KB)
                val ulMbps = measureUpload(512 * 1024) { pct, mbps ->
                    withContext(Dispatchers.Main) { callback.onUploadProgress(pct, mbps) }
                }

                val result = SpeedResult(dlMbps, ulMbps, latencyMs, jitterMs)
                Log.i(TAG, "Speed: ↓${dlMbps}Mbps ↑${ulMbps}Mbps latency=${latencyMs}ms jitter=${jitterMs}ms")
                withContext(Dispatchers.Main) { callback.onComplete(result) }
            } catch (e: Exception) {
                Log.e(TAG, "Speed test error: ${e.message}")
                withContext(Dispatchers.Main) { callback.onError(e.message ?: "Unknown error") }
            }
        }
    }

    private fun measureLatency(): Long {
        val t0 = System.currentTimeMillis()
        return try {
            val conn = URL("https://1.1.1.1").openConnection() as HttpsURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            conn.connect(); conn.disconnect()
            System.currentTimeMillis() - t0
        } catch (_: Exception) { -1L }
    }

    private suspend fun measureDownload(
        url: String,
        onProgress: suspend (Int, Float) -> Unit
    ): Float {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 30000
        val contentLen = conn.contentLength.coerceAtLeast(1)
        val buf = ByteArray(65536)
        var totalBytes = 0L
        val t0 = System.currentTimeMillis()
        conn.inputStream.use { stream ->
            var n: Int
            while (stream.read(buf).also { n = it } != -1) {
                totalBytes += n
                val elapsed = (System.currentTimeMillis() - t0) / 1000f
                val mbps = if (elapsed > 0) (totalBytes * 8 / elapsed / 1_000_000f) else 0f
                val pct = (totalBytes * 100 / contentLen).toInt().coerceIn(0, 100)
                onProgress(pct, mbps)
            }
        }
        val elapsed = (System.currentTimeMillis() - t0) / 1000f
        return if (elapsed > 0) (totalBytes * 8 / elapsed / 1_000_000f) else 0f
    }

    private suspend fun measureUpload(
        bytes: Int,
        onProgress: suspend (Int, Float) -> Unit
    ): Float {
        val data = ByteArray(bytes).also { java.security.SecureRandom().nextBytes(it) }
        val conn = URL(UL_URL).openConnection() as HttpsURLConnection
        conn.apply {
            requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Content-Length", bytes.toString())
            connectTimeout = 10000; readTimeout = 30000
        }
        val t0 = System.currentTimeMillis()
        val chunkSize = 8192
        var written = 0
        conn.outputStream.use { out ->
            while (written < bytes) {
                val chunk = minOf(chunkSize, bytes - written)
                out.write(data, written, chunk)
                written += chunk
                val elapsed = (System.currentTimeMillis() - t0) / 1000f
                val mbps = if (elapsed > 0) (written * 8 / elapsed / 1_000_000f) else 0f
                onProgress((written * 100 / bytes), mbps)
            }
        }
        val elapsed = (System.currentTimeMillis() - t0) / 1000f
        return if (elapsed > 0) (bytes * 8 / elapsed / 1_000_000f) else 0f
    }
}
