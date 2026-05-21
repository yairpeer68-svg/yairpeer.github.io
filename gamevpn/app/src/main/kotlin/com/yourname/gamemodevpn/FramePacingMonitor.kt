package com.yourname.gamemodevpn

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer

/**
 * Monitors frame pacing using Choreographer callbacks.
 * Detects dropped frames and jank (inconsistent frame timing).
 * Works even when monitoring another app via overlay.
 */
class FramePacingMonitor {

    private val choreographer = Choreographer.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private var active = false

    private var lastFrameNs = 0L
    private val frameDeltas = ArrayDeque<Long>(120)  // last 2 seconds at 60fps
    private var droppedFrames = 0
    private var totalFrames = 0

    data class FrameStats(
        val avgFps: Float,
        val avgFrameTimeMs: Float,
        val droppedFrames: Int,
        val jankPercent: Float,
        val stability: String
    )

    var onJankDetected: ((Int) -> Unit)? = null  // consecutive dropped frames

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return

            if (lastFrameNs > 0) {
                val deltaMs = (frameTimeNanos - lastFrameNs) / 1_000_000L
                frameDeltas.addLast(deltaMs)
                if (frameDeltas.size > 120) frameDeltas.removeFirst()
                totalFrames++

                // Dropped frame = delta > 2x expected (>33ms at 60fps)
                if (deltaMs > 33) {
                    droppedFrames++
                    val consecutive = deltaMs / 16
                    if (consecutive >= 3) onJankDetected?.invoke(consecutive.toInt())
                    Log.w(TAG, "Jank: ${deltaMs}ms frame")
                }
            }
            lastFrameNs = frameTimeNanos
            if (active) choreographer.postFrameCallback(this)
        }
    }

    fun start() {
        if (active) return
        active = true
        handler.post { choreographer.postFrameCallback(frameCallback) }
        Log.i(TAG, "✅ Frame pacing monitor started")
    }

    fun stop() {
        active = false
        choreographer.removeFrameCallback(frameCallback)
    }

    fun getStats(): FrameStats {
        if (frameDeltas.isEmpty()) return FrameStats(0f, 0f, 0, 0f, "אין נתונים")

        val avgDelta = frameDeltas.average().toFloat()
        val fps = if (avgDelta > 0) 1000f / avgDelta else 0f
        val jankPct = if (totalFrames > 0) droppedFrames.toFloat() / totalFrames * 100f else 0f

        val stability = when {
            jankPct < 2f  -> "🟢 חלק מאוד"
            jankPct < 5f  -> "🟡 טוב"
            jankPct < 10f -> "🟠 קצת Jank"
            else          -> "🔴 הרבה Jank"
        }
        return FrameStats(fps, avgDelta, droppedFrames, jankPct, stability)
    }

    fun reset() { frameDeltas.clear(); droppedFrames = 0; totalFrames = 0; lastFrameNs = 0 }
    companion object { const val TAG = "FramePacing" }
}
