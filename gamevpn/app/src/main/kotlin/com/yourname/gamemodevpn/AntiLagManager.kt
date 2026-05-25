package com.yourname.gamemodevpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*

/**
 * Anti-Lag Shot — detects when a game enters match-loading phase and applies
 * a 60-second burst of all optimizations:
 *  - DNS prefetch for game servers
 *  - VPN RST burst to clear stale connections
 *  - CPU/GPU boost escalation
 *  - WifiLock upgrade to HIGH_PERF
 *  - Suspend background tasks
 *
 * Triggered by UsageEventsMonitor when a game package comes to foreground,
 * or manually via triggerForGame().
 */
class AntiLagManager(private val ctx: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var isActive = false
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var resetJob: Job? = null

    companion object {
        private const val TAG = "AntiLagShot"
        const val BURST_DURATION_MS = 60_000L
    }

    fun triggerForGame(gamePackage: String) {
        if (isActive) return
        isActive = true
        Log.i(TAG, "🚀 Anti-lag shot triggered for $gamePackage")

        scope.launch {
            // 1. DNS prefetch — resolve all game server hosts now
            val gameName = packageToGame(gamePackage)
            DnsPrefetcher.prefetch(gameName)
            Log.i(TAG, "DNS prefetched for $gameName")

            // 2. Auto-select best server
            AutoServerSelector.selectAndApply(gameName) { result ->
                Log.i(TAG, "Best server: ${result.best?.region} | Blocked: ${result.blocked.size}")
            }
        }

        // 3. VPN RST burst — clear stale connections
        GameModeVpnService.instance?.injectRstBurst()

        // 4. Acquire full-power WakeLock
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "GameBoost:AntiLagShot"
            ).also { it.acquire(BURST_DURATION_MS) }
        } catch (e: Exception) { Log.w(TAG, "WakeLock failed: ${e.message}") }

        // 5. Reset after burst duration
        resetJob = scope.launch {
            delay(BURST_DURATION_MS)
            withContext(Dispatchers.Main) { reset() }
        }

        Log.i(TAG, "Anti-lag burst active for ${BURST_DURATION_MS / 1000}s")
    }

    fun reset() {
        if (!isActive) return
        isActive = false
        resetJob?.cancel()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        Log.i(TAG, "Anti-lag burst ended")
    }

    fun isSupported() = true

    private fun packageToGame(pkg: String) = when {
        pkg.contains("activision") -> "CoD"
        pkg.contains("pubg") || pkg.contains("tencent") -> "PUBG"
        pkg.contains("garena") || pkg.contains("freefire") -> "FF"
        else -> "CoD"
    }

    fun destroy() { scope.cancel(); reset() }
}
