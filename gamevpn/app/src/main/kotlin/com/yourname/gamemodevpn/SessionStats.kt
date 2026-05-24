package com.yourname.gamemodevpn

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class SessionStats(private val ctx: Context, private val game: String) {

    private val db = SessionDatabase(ctx)
    private val pings = java.util.Collections.synchronizedList(mutableListOf<Int>())
    private val jitters = java.util.Collections.synchronizedList(mutableListOf<Int>())
    private val packetsTotal = AtomicInteger(0)
    private val packetsLost = AtomicInteger(0)
    @Volatile private var startTime = 0L
    @Volatile private var lastPing = 0

    private val prefs = ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
    private val spikeThreshold get() = prefs.getInt("spike_threshold", 80)

    var onSpikeDetected: ((Int) -> Unit)? = null

    fun start() { startTime = System.currentTimeMillis() }

    fun recordPing(ms: Int) {
        pings.add(ms)
        val jitter = if (lastPing > 0) abs(ms - lastPing) else 0
        jitters.add(jitter)
        lastPing = ms
        packetsTotal.incrementAndGet()

        if (ms > spikeThreshold && pings.size > 3) {
            Log.w("SessionStats", "PING SPIKE: ${ms}ms (threshold: ${spikeThreshold}ms)")
            onSpikeDetected?.invoke(ms)
            vibrateSpike()
        }
    }

    fun recordPacketLoss() {
        packetsLost.incrementAndGet()
        packetsTotal.incrementAndGet()
    }

    fun finish(): SessionRecord? {
        if (pings.isEmpty() || startTime == 0L) return null
        val pingSnapshot = synchronized(pings) { pings.toList() }
        val jitterSnapshot = synchronized(jitters) { jitters.toList() }
        if (pingSnapshot.isEmpty()) return null
        val total = packetsTotal.get()
        val lost = packetsLost.get()
        val rec = SessionRecord(
            game        = game,
            startTime   = startTime,
            durationSec = ((System.currentTimeMillis() - startTime) / 1000).toInt(),
            avgPing     = pingSnapshot.average().toInt(),
            minPing     = pingSnapshot.minOrNull() ?: 0,
            maxPing     = pingSnapshot.maxOrNull() ?: 0,
            packetLoss  = if (total > 0) lost.toFloat() / total * 100f else 0f,
            avgJitter   = if (jitterSnapshot.isNotEmpty()) jitterSnapshot.average().toInt() else 0
        )
        db.insert(rec)
        Log.i("SessionStats", "Session saved: avg=${rec.avgPing}ms loss=${rec.packetLoss}%")
        return rec
    }

    fun getLivePingHistory(): List<Int> = synchronized(pings) { pings.takeLast(60).toList() }
    fun getCurrentJitter(): Int = jitters.lastOrNull() ?: 0
    fun getPacketLossPercent(): Float {
        val total = packetsTotal.get()
        return if (total > 0) packetsLost.get().toFloat() / total * 100f else 0f
    }
    fun getAveragePing(): Int = synchronized(pings) {
        if (pings.isEmpty()) 0 else pings.average().toInt()
    }

    private fun vibrateSpike() {
        try {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80), -1))
            else @Suppress("DEPRECATION") vib.vibrate(longArrayOf(0, 80, 60, 80), -1)
        } catch (_: Exception) { }
    }
}
