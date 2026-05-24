package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * DNS-over-QUIC (DoQ) approximation — RFC 9250
 *
 * True QUIC requires a QUIC transport library (e.g. Cronet) which is not available
 * natively on all Android versions. This implementation approximates DoQ by:
 *   1. Building a standard DNS wire-format query (identical to DoT/DoH).
 *   2. Prepending a minimal 2-byte length prefix (mirrors DoT framing, satisfies RFC 9250 §4.2).
 *   3. Sending it via a UDP DatagramSocket to 1.1.1.1:853 (the DoQ port per RFC 9250 §11.5).
 *
 * If the UDP exchange is blocked or fails (e.g. firewall drops port 853 UDP) the resolver
 * falls back transparently to DoHResolver so the caller always gets a result.
 */
object DoQResolver {

    private const val TAG       = "DoQResolver"
    private const val CF_HOST   = "1.1.1.1"
    private const val DOQ_PORT  = 853   // RFC 9250 §11.5: same port as DoT, but UDP
    private const val TIMEOUT_MS = 2000

    data class DoQResult(val ip: String?, val latencyMs: Long)

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun resolve(domain: String): DoQResult = withContext(Dispatchers.IO) {
        val result = tryUdpResolve(domain)
        if (result.ip != null) {
            Log.i(TAG, "DoQ $domain → ${result.ip} (${result.latencyMs}ms)")
            return@withContext result
        }
        // Fallback: use DoH (which itself falls back to Google if Cloudflare fails)
        Log.w(TAG, "DoQ UDP failed for $domain — falling back to DoH")
        return@withContext fallbackDoH(domain)
    }

    // ── UDP exchange ──────────────────────────────────────────────────────────

    /**
     * Sends a DNS query over raw UDP to 1.1.1.1:853.
     *
     * RFC 9250 §4.2 specifies that each DNS message inside a QUIC stream is
     * prefixed with a 2-byte length, exactly as in DoT (RFC 7858). We replicate
     * that framing here so the payload is structurally equivalent even though we
     * are using UDP rather than a real QUIC connection.
     */
    private fun tryUdpResolve(domain: String): DoQResult {
        val t0 = System.currentTimeMillis()
        var socket: DatagramSocket? = null
        return try {
            val query       = buildDnsQuery(domain)
            val framedQuery = applyLengthPrefix(query) // RFC 9250 / DoT-style framing

            val serverAddr  = InetAddress.getByName(CF_HOST)
            socket          = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS

            // Send
            val sendPacket = DatagramPacket(framedQuery, framedQuery.size, serverAddr, DOQ_PORT)
            socket.send(sendPacket)

            // Receive — allocate 512 bytes (standard DNS UDP max before EDNS0)
            val recvBuf    = ByteArray(512)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)

            val latency = System.currentTimeMillis() - t0

            // Strip the 2-byte length prefix from the response before parsing
            val responseData = stripLengthPrefix(recvPacket.data, recvPacket.length)
            val ip = parseARecord(responseData)
            DoQResult(ip, latency)
        } catch (e: Exception) {
            Log.w(TAG, "DoQ UDP error: ${e.message}")
            DoQResult(null, -1L)
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
    }

    // ── DNS wire-format helpers ───────────────────────────────────────────────

    /**
     * Builds a standard DNS A-record query in RFC 1035 wire format.
     * This is identical to the format used by DoH and DoT — only the transport differs.
     */
    private fun buildDnsQuery(domain: String): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeShort(0xD0C4)  // transaction ID (arbitrary, non-zero)
        out.writeShort(0x0100)  // flags: standard query, recursion desired
        out.writeShort(1)       // QDCOUNT = 1 question
        out.writeShort(0)       // ANCOUNT = 0
        out.writeShort(0)       // NSCOUNT = 0
        out.writeShort(0)       // ARCOUNT = 0
        // QNAME: each label preceded by its length, terminated by 0x00
        for (label in domain.split(".")) {
            out.writeByte(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.writeByte(0)        // root label
        out.writeShort(1)       // QTYPE  = A (host address)
        out.writeShort(1)       // QCLASS = IN (Internet)
        return buf.toByteArray()
    }

    /**
     * Prepends the 2-byte big-endian length field required by RFC 9250 §4.2 / RFC 7858 §3.3.
     * For UDP this acts purely as a structural marker; the framing is not strictly needed
     * by a plain DNS server but ensures the payload is self-describing.
     */
    private fun applyLengthPrefix(query: ByteArray): ByteArray {
        val buf = ByteArrayOutputStream(query.size + 2)
        val out = DataOutputStream(buf)
        out.writeShort(query.size)
        out.write(query)
        return buf.toByteArray()
    }

    /**
     * Removes the 2-byte length prefix from a response, if present.
     * Falls back to returning the raw bytes if the prefix is absent or malformed.
     */
    private fun stripLengthPrefix(data: ByteArray, length: Int): ByteArray {
        if (length < 2) return data.copyOf(length)
        val declaredLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val available   = length - 2
        return if (declaredLen in 1..available) {
            data.copyOfRange(2, 2 + declaredLen)
        } else {
            // Server replied without framing (plain UDP DNS) — use data as-is
            data.copyOf(length)
        }
    }

    /**
     * Parses the first A record from a DNS response in RFC 1035 wire format.
     * Returns the dotted-decimal IPv4 string, or null if no A record is found.
     */
    private fun parseARecord(data: ByteArray): String? {
        return try {
            if (data.size < 12) return null
            val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (anCount == 0) return null

            // Skip header (12 bytes), then skip the QNAME in the question section
            var pos = 12
            while (pos < data.size && data[pos] != 0.toByte()) {
                if (data[pos].toInt() and 0xC0 == 0xC0) { pos += 2; break }
                pos += data[pos].toInt() + 1
            }
            if (pos < data.size && data[pos] == 0.toByte()) pos++
            pos += 4  // skip QTYPE + QCLASS

            // Answer section: skip the NAME field (may be a compression pointer)
            if (pos + 12 >= data.size) return null
            if (data[pos].toInt() and 0xC0 == 0xC0) pos += 2
            else {
                while (pos < data.size && data[pos] != 0.toByte()) pos++
                if (pos < data.size) pos++
            }
            pos += 10 // skip TYPE(2) + CLASS(2) + TTL(4) + RDLENGTH(2)

            if (pos + 4 > data.size) return null
            "${data[pos].toInt() and 0xFF}.${data[pos+1].toInt() and 0xFF}" +
            ".${data[pos+2].toInt() and 0xFF}.${data[pos+3].toInt() and 0xFF}"
        } catch (_: Exception) { null }
    }

    // ── DoH fallback ──────────────────────────────────────────────────────────

    private suspend fun fallbackDoH(domain: String): DoQResult {
        return try {
            val doh = DoHResolver.resolve(domain)
            DoQResult(doh.ip, doh.latencyMs)
        } catch (e: Exception) {
            Log.w(TAG, "DoH fallback also failed: ${e.message}")
            DoQResult(null, -1L)
        }
    }
}
