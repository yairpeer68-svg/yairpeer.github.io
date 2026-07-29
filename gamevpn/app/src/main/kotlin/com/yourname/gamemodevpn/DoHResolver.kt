package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * DNS-over-HTTPS resolver (RFC 8484)
 * Sends encrypted DNS queries to Cloudflare 1.1.1.1/dns-query
 * Zero DNS leaks — bypasses ISP sniffing completely
 */
object DoHResolver {

    private const val TAG = "DoHResolver"
    private const val CF_DOH  = "https://1.1.1.1/dns-query"
    private const val G_DOH   = "https://8.8.8.8/dns-query"

    @Volatile var preferDoT = false

    data class DoHResult(val ip: String?, val latencyMs: Long, val provider: String)

    // Build RFC 8484 DNS wire format query for A record
    private fun buildDnsQuery(domain: String): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeShort(0x1234)  // transaction ID
        out.writeShort(0x0100)  // flags: standard query, recursion desired
        out.writeShort(1)       // 1 question
        out.writeShort(0)       // 0 answers
        out.writeShort(0)       // 0 authority
        out.writeShort(0)       // 0 additional

        // Encode domain name
        for (part in domain.split(".")) {
            out.writeByte(part.length)
            out.write(part.toByteArray(Charsets.US_ASCII))
        }
        out.writeByte(0)        // root label
        out.writeShort(1)       // type A
        out.writeShort(1)       // class IN
        return buf.toByteArray()
    }

    // Parse A record from DNS response
    private fun parseARecord(data: ByteArray): String? {
        try {
            if (data.size < 12) return null
            val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (anCount == 0) return null
            // Skip header (12 bytes) + question section
            var pos = 12
            // Skip question name
            while (pos < data.size && data[pos] != 0.toByte()) {
                if (data[pos].toInt() and 0xC0 == 0xC0) { pos += 2; break }
                pos += data[pos].toInt() + 1
            }
            if (data[pos] == 0.toByte()) pos++
            pos += 4 // skip QTYPE + QCLASS
            // Parse answer
            if (pos + 12 >= data.size) return null
            if (data[pos].toInt() and 0xC0 == 0xC0) pos += 2 else while (pos < data.size && data[pos] != 0.toByte()) pos++
            pos += 10 // skip TYPE, CLASS, TTL, RDLENGTH header
            if (pos + 4 > data.size) return null
            return "${data[pos].toInt() and 0xFF}.${data[pos+1].toInt() and 0xFF}.${data[pos+2].toInt() and 0xFF}.${data[pos+3].toInt() and 0xFF}"
        } catch (e: Exception) { return null }
    }

    suspend fun resolve(domain: String, provider: String = CF_DOH): DoHResult = withContext(Dispatchers.IO) {
        // Use DNS-over-TLS if preferred
        if (preferDoT) {
            val dotResult = try { DoTResolver.resolve(domain) } catch (_: Exception) { null }
            if (dotResult?.ip != null) return@withContext DoHResult(dotResult.ip, dotResult.latencyMs, "DoT")
            Log.i(TAG, "DoT failed, falling back to DoH")
        }
        val result = doResolve(domain, provider)
        if (result.ip != null) return@withContext result
        // Fallback to Google DoH if Cloudflare failed
        if (provider == CF_DOH) {
            Log.i(TAG, "Cloudflare failed, trying Google DoH...")
            return@withContext doResolve(domain, G_DOH)
        }
        return@withContext result
    }

    private fun doResolve(domain: String, provider: String): DoHResult {
        val query = buildDnsQuery(domain)
        val t0 = System.currentTimeMillis()
        return try {
            val conn = CertPinner.openPinnedConnection(provider).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
                setRequestProperty("User-Agent", "GameBoost/9.0")
                connectTimeout = 3000; readTimeout = 3000
                doOutput = true
            }
            conn.outputStream.use { it.write(query) }
            val response = conn.inputStream.use { it.readBytes() }
            val latency = System.currentTimeMillis() - t0
            val ip = parseARecord(response)
            Log.i(TAG, "DoH $domain → $ip (${latency}ms) via ${if (provider.contains("1.1.1.1")) "CF" else "Google"}")
            DoHResult(ip, latency, if (provider.contains("1.1.1.1")) "Cloudflare" else "Google")
        } catch (e: Exception) {
            Log.w(TAG, "DoH error ($provider): ${e.message}")
            DoHResult(null, -1L, "error")
        }
    }

    // Leak test: resolve via DoH vs system DNS, compare results
    suspend fun runLeakTest(): LeakTestResult = withContext(Dispatchers.IO) {
        val testDomain = "whoami.cloudflare.com"
        val doh = resolve(testDomain, CF_DOH)
        val systemDns = try {
            val addr = java.net.InetAddress.getByName(testDomain)
            addr.hostAddress ?: "unknown"
        } catch (e: Exception) { "error" }
        val leaked = doh.ip != null && systemDns != "error" && doh.ip != systemDns
        LeakTestResult(doh.ip, systemDns, leaked, doh.latencyMs)
    }

    data class LeakTestResult(
        val dohIp: String?, val systemIp: String,
        val leaked: Boolean, val latencyMs: Long
    )
}
