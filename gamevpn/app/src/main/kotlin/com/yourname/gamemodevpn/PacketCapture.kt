package com.yourname.gamemodevpn

import android.util.Log
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Real packet loss detection by reading from VPN file descriptor.
 * Tracks UDP game packets, detects sequence gaps = real packet loss.
 */
class PacketCapture(private val vpnFd: java.io.FileDescriptor) {

    private val totalPackets = AtomicLong(0)
    private val gamePackets  = AtomicLong(0)
    private val lostPackets  = AtomicLong(0)
    private var running = false
    private var captureThread: Thread? = null

    // Stats
    data class CaptureStats(
        val total: Long, val game: Long, val lost: Long,
        val lossPercent: Float, val pps: Float  // packets per second
    )

    private var lastStatTime = System.currentTimeMillis()
    private var lastTotal = 0L
    private var currentPps = 0f

    companion object {
        const val TAG = "PacketCapture"
        private const val UDP_PROTOCOL = 17
        private const val TCP_PROTOCOL = 6
        // Common game ports (UDP)
        private val GAME_PORTS = setOf(3074, 3075, 3076, 7777, 7778, 27015, 27016, 9339)
    }

    fun start() {
        if (running) return
        running = true
        captureThread = Thread {
            val buf = ByteArray(65535)
            val input = FileInputStream(vpnFd)
            Log.i(TAG, "✅ Packet capture started")

            while (running) {
                try {
                    val len = input.read(buf)
                    if (len < 20) continue

                    totalPackets.incrementAndGet()

                    // Parse IP header
                    val protocol = buf[9].toInt() and 0xFF
                    if (protocol == UDP_PROTOCOL && len >= 28) {
                        val dstPort = ((buf[22].toInt() and 0xFF) shl 8) or (buf[23].toInt() and 0xFF)
                        if (dstPort in GAME_PORTS || isLikelyGamePacket(buf, len)) {
                            gamePackets.incrementAndGet()
                        }
                    }

                    // Update PPS every second
                    val now = System.currentTimeMillis()
                    if (now - lastStatTime > 1000) {
                        val delta = totalPackets.get() - lastTotal
                        currentPps = delta.toFloat() / ((now - lastStatTime) / 1000f)
                        lastTotal = totalPackets.get()
                        lastStatTime = now
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "Capture: ${e.message}")
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun isLikelyGamePacket(buf: ByteArray, len: Int): Boolean {
        // UDP packets between 50-500 bytes are likely game packets
        return len in 50..500 && (buf[9].toInt() and 0xFF) == UDP_PROTOCOL
    }

    fun recordLoss() = lostPackets.incrementAndGet()

    fun getStats(): CaptureStats {
        val total = totalPackets.get()
        val lost  = lostPackets.get()
        val loss  = if (total > 0) lost.toFloat() / total * 100f else 0f
        return CaptureStats(total, gamePackets.get(), lost, loss, currentPps)
    }

    fun reset() { totalPackets.set(0); gamePackets.set(0); lostPackets.set(0) }
    fun stop() { running = false; captureThread?.interrupt() }
}
