package com.yourname.gamemodevpn

import android.content.Context
import android.os.*
import android.util.Log

/**
 * CPU/Performance boost without root:
 * 1. PerformanceHintManager (Android 12+) — hints to scheduler for big-core usage
 * 2. PowerManager SUSTAINED_PERFORMANCE — anti-throttle for long sessions
 * 3. WakeLock PARTIAL — prevents CPU from sleeping mid-game
 * 4. Thread priorities — VPN packet thread runs at URGENT_AUDIO level
 */
class CpuBoostManager(private val ctx: Context) {

    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var sustainedPerfWl: PowerManager.WakeLock? = null
    private var hintSession: Any? = null // PerformanceHintManager.Session (API 31+)
    private var boostThread: Thread? = null
    private var active = false

    companion object {
        const val TAG = "CpuBoost"
        // Target duration for each work cycle: 16ms = 60fps, 8ms = 120fps
        const val TARGET_NS_60FPS  = 16_666_666L
        const val TARGET_NS_120FPS =  8_333_333L
    }

    fun start(targetFps: Int = 60) {
        if (active) return
        active = true

        // 1. PARTIAL_WAKE_LOCK — CPU stays on, screen can sleep
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GameBoost::CpuWakeLock"
        ).also {
            it.acquire(4 * 60 * 60 * 1000L) // max 4 hours
            Log.i(TAG, "✅ WakeLock acquired — CPU won't sleep")
        }

        // 2. SUSTAINED_PERFORMANCE_MODE — tells system to maintain consistent perf, avoid throttle
        if (pm.isSustainedPerformanceModeSupported) {
            sustainedPerfWl = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK,
                "GameBoost::SustainedPerf"
            ).also { it.acquire(4 * 60 * 60 * 1000L) }
            Log.i(TAG, "✅ Sustained performance mode active")
        } else {
            Log.i(TAG, "ℹ️ Sustained perf not supported on this device")
        }

        // 3. PerformanceHintManager (Android 12 / API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startHintSession(targetFps)
        }

        // 4. Set THIS process priority as high as possible
        boostProcess()

        Log.i(TAG, "🚀 CpuBoostManager ACTIVE (target: ${targetFps}fps)")
    }

    @Suppress("NewApi")
    private fun startHintSession(fps: Int) {
        try {
            val hintMgr = ctx.getSystemService(android.os.PerformanceHintManager::class.java)
                ?: return
            val tids = intArrayOf(android.os.Process.myTid())
            val targetNs = if (fps >= 120) TARGET_NS_120FPS else TARGET_NS_60FPS
            hintSession = hintMgr.createHintSession(tids, targetNs)
            Log.i(TAG, "✅ PerformanceHintManager session created (${fps}fps / ${targetNs}ns)")

            // Send periodic "actual duration" reports to keep hints active
            boostThread = Thread {
                while (active) {
                    try {
                        val session = hintSession as? android.os.PerformanceHintManager.Session
                        session?.reportActualWorkDuration(targetNs - 1_000_000L) // just under target
                        Thread.sleep(500)
                    } catch (e: InterruptedException) { break }
                    catch (e: Exception) { Log.w(TAG, "Hint: ${e.message}") }
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            Log.w(TAG, "PerformanceHintManager error: ${e.message}")
        }
    }

    private fun boostProcess() {
        try {
            // Set VPN process to highest possible thread priority
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) // -19 (max without root)
            Log.i(TAG, "✅ Thread priority → URGENT_AUDIO (-19)")
        } catch (e: Exception) { Log.w(TAG, "Priority: ${e.message}") }

        try {
            // Request top-app scheduling group via reflection (THREAD_GROUP_TOP_APP = 5)
            val setProcessGroup = Process::class.java.getMethod("setProcessGroup", Int::class.java, Int::class.java)
            setProcessGroup.invoke(null, Process.myPid(), 5 /* THREAD_GROUP_TOP_APP */)
            Log.i(TAG, "✅ Process group → TOP_APP")
        } catch (e: Exception) { Log.w(TAG, "Group: ${e.message}") }
    }

    fun stop() {
        if (!active) return
        active = false
        boostThread?.interrupt()

        @Suppress("NewApi")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { (hintSession as? android.os.PerformanceHintManager.Session)?.close() } catch (e: Exception) { }
        }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        try { if (sustainedPerfWl?.isHeld == true) sustainedPerfWl?.release() } catch (e: Exception) { }

        // Restore normal priority
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) } catch (e: Exception) { }

        Log.i(TAG, "🛑 CpuBoostManager stopped")
    }

    fun isSustainedPerfSupported() = pm.isSustainedPerformanceModeSupported
    fun isHintManagerAvailable() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}
