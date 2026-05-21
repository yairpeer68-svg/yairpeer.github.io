package com.yourname.gamemodevpn

import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Samples per-UID and total traffic every second to produce real-time
 * RX / TX rate in Kbps.
 */
class BandwidthMonitor(private val uid: Int = android.os.Process.myUid()) {

    data class Sample(val rxKbps: Float, val txKbps: Float, val totalRxMb: Float, val totalTxMb: Float)

    var onSample: ((Sample) -> Unit)? = null

    private val running = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var prevRxBytes = 0L
    private var prevTxBytes = 0L
    private var prevTime = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val now = System.currentTimeMillis()
            val rxBytes = TrafficStats.getUidRxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
            val txBytes = TrafficStats.getUidTxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L

            if (prevTime > 0) {
                val dtSec = (now - prevTime) / 1000f
                val rxKbps = ((rxBytes - prevRxBytes) * 8 / 1000f / dtSec).coerceAtLeast(0f)
                val txKbps = ((txBytes - prevTxBytes) * 8 / 1000f / dtSec).coerceAtLeast(0f)
                val totalRxMb = rxBytes / 1024f / 1024f
                val totalTxMb = txBytes / 1024f / 1024f
                onSample?.invoke(Sample(rxKbps, txKbps, totalRxMb, totalTxMb))
                Log.v(TAG, "RX=${rxKbps.toInt()}Kbps TX=${txKbps.toInt()}Kbps")
            }

            prevRxBytes = rxBytes
            prevTxBytes = txBytes
            prevTime = now

            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        prevTime = 0
        prevRxBytes = 0
        prevTxBytes = 0
        handler.post(ticker)
        Log.i(TAG, "BandwidthMonitor started (uid=$uid)")
    }

    fun stop() {
        running.set(false)
        handler.removeCallbacks(ticker)
    }

    companion object {
        private const val TAG = "BandwidthMonitor"
        private const val INTERVAL_MS = 1000L
    }
}
