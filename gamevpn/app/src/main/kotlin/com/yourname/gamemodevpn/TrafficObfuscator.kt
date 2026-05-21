package com.yourname.gamemodevpn

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * Traffic obfuscation — wraps packets in a fake TLS ClientHello header
 * and applies XOR scrambling so that ISP DPI classifiers see HTTPS traffic.
 *
 * NOT a real TLS handshake — purely a DPI bypass layer.
 * The VPN endpoint must use the same obfuscation to decode.
 */
object TrafficObfuscator {

    private const val TAG = "TrafficObfuscator"
    private val rng = SecureRandom()

    // XOR key rotated per session
    @Volatile private var sessionKey: ByteArray = generateKey()

    private fun generateKey(): ByteArray = ByteArray(32).also { rng.nextBytes(it) }

    fun newSession() {
        sessionKey = generateKey()
        Log.i(TAG, "New obfuscation session key generated")
    }

    /**
     * Obfuscate [data]: XOR with session key + prepend fake TLS record header.
     * Output: [5-byte TLS record header][XOR-scrambled payload]
     */
    fun obfuscate(data: ByteArray): ByteArray {
        val xored = xorWithKey(data, sessionKey)
        // Fake TLS Application Data record header: 0x17 0x03 0x03 + 2-byte length
        val header = byteArrayOf(0x17, 0x03, 0x03,
            ((xored.size shr 8) and 0xFF).toByte(),
            (xored.size and 0xFF).toByte()
        )
        return header + xored
    }

    /**
     * Deobfuscate: strip 5-byte header, XOR back.
     */
    fun deobfuscate(data: ByteArray): ByteArray? {
        if (data.size < 5) return null
        if (data[0] != 0x17.toByte()) {
            Log.w(TAG, "Unexpected TLS record type: 0x${data[0].toInt().and(0xFF).toString(16)}")
            return null
        }
        val payloadLen = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        if (data.size < 5 + payloadLen) return null
        return xorWithKey(data.copyOfRange(5, 5 + payloadLen), sessionKey)
    }

    private fun xorWithKey(data: ByteArray, key: ByteArray): ByteArray {
        val result = ByteArray(data.size)
        for (i in data.indices) result[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        return result
    }

    /** Wrap an OutputStream so all writes are automatically obfuscated. */
    fun wrap(out: OutputStream): OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            val obf = obfuscate(b.copyOfRange(off, off + len))
            out.write(obf)
        }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    /** Wrap an InputStream so all reads are automatically deobfuscated. */
    fun wrap(input: InputStream): InputStream = object : InputStream() {
        private val buf = java.io.ByteArrayInputStream(ByteArray(0))
        private val pending = java.io.ByteArrayOutputStream()

        override fun read(): Int {
            val arr = ByteArray(1)
            return if (read(arr) == -1) -1 else arr[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val raw = ByteArray(len + 5)
            val n = input.read(raw, 0, raw.size)
            if (n <= 0) return n
            val plain = deobfuscate(raw.copyOf(n)) ?: return -1
            System.arraycopy(plain, 0, b, off, minOf(plain.size, len))
            return minOf(plain.size, len)
        }
    }
}
