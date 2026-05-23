package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream as DOS
import javax.net.ssl.SSLSocketFactory

/**
 * DNS-over-TLS (DoT) resolver — RFC 7858
 * Connects to Cloudflare 1.1.1.1:853 or Google 8.8.8.8:853 over TLS.
 * Lower overhead than DoH (no HTTP framing), same security.
 * Wire format: 2-byte length prefix + standard DNS message.
 */
object DoTResolver {

    private const val TAG = "DoTResolver"
    private const val CF_HOST = "1.1.1.1"
    private const val G_HOST  = "8.8.8.8"
    private const val DOT_PORT = 853
    private const val TIMEOUT_MS = 3000

    data class DoTResult(val ip: String?, val latencyMs: Long, val provider: String)

    suspend fun resolve(domain: String): DoTResult = withContext(Dispatchers.IO) {
        val result = doResolve(domain, CF_HOST)
        if (result.ip != null) return@withContext result
        Log.i(TAG, "Cloudflare DoT failed, trying Google...")
        return@withContext doResolve(domain, G_HOST)
    }

    private fun doResolve(domain: String, host: String): DoTResult {
        val t0 = System.currentTimeMillis()
        return try {
            val factory = CertPinner.createPinnedSSLContext().socketFactory
            val socket = factory.createSocket(host, DOT_PORT) as javax.net.ssl.SSLSocket
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()

            val out = DataOutputStream(socket.outputStream)
            val inp = DataInputStream(socket.inputStream)

            val query = buildDnsQuery(domain)
            out.writeShort(query.size)   // RFC 7858: 2-byte length prefix
            out.write(query)
            out.flush()

            val responseLen = inp.readUnsignedShort()
            val response = ByteArray(responseLen)
            inp.readFully(response)
            socket.close()

            val latency = System.currentTimeMillis() - t0
            val ip = parseARecord(response)
            val provider = if (host == CF_HOST) "Cloudflare" else "Google"
            Log.i(TAG, "DoT $domain → $ip (${latency}ms) via $provider")
            DoTResult(ip, latency, provider)
        } catch (e: Exception) {
            Log.w(TAG, "DoT error ($host): ${e.message}")
            DoTResult(null, -1L, "error")
        }
    }

    private fun buildDnsQuery(domain: String): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DOS(buf)
        out.writeShort(0xABCD)  // transaction ID
        out.writeShort(0x0100)  // standard query, recursion desired
        out.writeShort(1); out.writeShort(0); out.writeShort(0); out.writeShort(0)
        for (part in domain.split(".")) {
            out.writeByte(part.length)
            out.write(part.toByteArray(Charsets.US_ASCII))
        }
        out.writeByte(0)
        out.writeShort(1)  // type A
        out.writeShort(1)  // class IN
        return buf.toByteArray()
    }

    private fun parseARecord(data: ByteArray): String? {
        return try {
            if (data.size < 12) return null
            val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (anCount == 0) return null
            var pos = 12
            while (pos < data.size && data[pos] != 0.toByte()) {
                if (data[pos].toInt() and 0xC0 == 0xC0) { pos += 2; break }
                pos += data[pos].toInt() + 1
            }
            if (pos < data.size && data[pos] == 0.toByte()) pos++
            pos += 4
            if (pos + 12 >= data.size) return null
            if (data[pos].toInt() and 0xC0 == 0xC0) pos += 2
            else while (pos < data.size && data[pos] != 0.toByte()) pos++
            pos += 10
            if (pos + 4 > data.size) return null
            "${data[pos].toInt() and 0xFF}.${data[pos+1].toInt() and 0xFF}.${data[pos+2].toInt() and 0xFF}.${data[pos+3].toInt() and 0xFF}"
        } catch (_: Exception) { null }
    }
}
