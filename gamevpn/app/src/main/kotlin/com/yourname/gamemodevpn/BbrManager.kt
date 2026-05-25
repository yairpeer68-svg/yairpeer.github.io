package com.yourname.gamemodevpn

import android.os.Build
import android.util.Log
import java.net.Socket

/**
 * BBR Congestion Control manager.
 * BBR (Bottleneck Bandwidth and RTT) outperforms CUBIC on high-jitter networks.
 * On Android, TCP congestion control is set via socket options (requires API 31+
 * or root for system-wide change). This sets per-socket options where possible.
 *
 * For game servers using QUIC/UDP, BBR-like behavior is achieved by limiting
 * send rate based on measured bandwidth and RTT.
 */
object BbrManager {

    private const val TAG = "BBR"

    data class BbrStats(
        val estimatedBwKbps: Long,
        val minRttMs: Long,
        val inflightPackets: Int
    )

    @Volatile private var minRttMs = Long.MAX_VALUE
    @Volatile private var recentRttMs = 0L
    private val rttHistory = ArrayDeque<Long>(100)

    /**
     * Apply BBR-friendly socket options to a TCP socket.
     * Sets TCP_NODELAY + optimized buffer sizes.
     */
    fun configureTcpSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true       // disable Nagle — critical for gaming
            socket.soTimeout = 5000
            socket.setSendBufferSize(65536)
            socket.setReceiveBufferSize(65536)
            Log.d(TAG, "TCP socket configured (NoDelay + 64KB buffers)")
        } catch (e: Exception) {
            Log.w(TAG, "Socket config: ${e.message}")
        }
    }

    /**
     * Record an RTT sample for BBR bandwidth estimation.
     */
    fun recordRtt(rttMs: Long) {
        if (rttMs <= 0) return
        recentRttMs = rttMs
        if (rttMs < minRttMs) minRttMs = rttMs
        synchronized(rttHistory) {
            if (rttHistory.size >= 100) rttHistory.removeFirst()
            rttHistory.addLast(rttMs)
        }
    }

    /**
     * BBR send pacing: returns the recommended inter-packet delay in ms
     * to avoid filling the bottleneck buffer (= avoid bufferbloat).
     */
    fun getPacingDelayMs(packetSizeBytes: Int): Long {
        if (minRttMs == Long.MAX_VALUE || minRttMs <= 0) return 0L
        val estimatedBwBps = (packetSizeBytes * 8 * 1000L / minRttMs).coerceAtLeast(1)
        val pacingRateMs = (packetSizeBytes * 8 * 1000L / estimatedBwBps).coerceIn(0, 50)
        return pacingRateMs
    }

    fun getStats(): BbrStats {
        val recentRtts = synchronized(rttHistory) { rttHistory.toList() }
        val estimatedBw = if (recentRtts.isNotEmpty() && minRttMs > 0) {
            1500L * 8 * 1000L / minRttMs  // estimate from min RTT + 1500B packets
        } else 0L
        return BbrStats(estimatedBw / 1000, minRttMs.takeIf { it != Long.MAX_VALUE } ?: -1, recentRtts.size)
    }

    fun reset() {
        minRttMs = Long.MAX_VALUE
        recentRttMs = 0
        synchronized(rttHistory) { rttHistory.clear() }
    }

    fun isAvailable() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
}
