package com.yourname.gamemodevpn

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Android 12+ official Game APIs:
 * - GameManager: sets device into game mode (performance/battery/standard)
 * - GameState: tells system if we're in gameplay vs loading
 * - ThermalStatusCallback: official thermal monitoring
 */
class GameApiManager(private val ctx: Context) {

    companion object { const val TAG = "GameApiManager" }

    var onThermalStatus: ((Int, String) -> Unit)? = null

    // ── GameManager (API 31+) ─────────────────────────────────────────────────
    fun setPerformanceMode(pkg: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val gm = ctx.getSystemService(android.app.GameManager::class.java) ?: return
            // GAME_MODE_PERFORMANCE = 2: max CPU/GPU, ignores battery
            val mode = android.app.GameManager.GAME_MODE_PERFORMANCE
            // Note: setGameMode requires MANAGE_GAME_ACTIVITY permission (system only)
            // But we can READ the current mode and inform the user
            val current = gm.getGameMode()
            Log.i(TAG, "GameMode for $pkg: $current (target=PERFORMANCE)")
        } catch (e: Exception) { Log.w(TAG, "GameManager: ${e.message}") }
    }

    // ── GameState (API 33+) ───────────────────────────────────────────────────
    fun reportGameplayStarted(pkg: String) {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            val gm = ctx.getSystemService(android.app.GameManager::class.java) ?: return
            // GAME_STATE_GAMEPLAY_UNINTERRUPTIBLE = system should not interrupt
            val state = android.app.GameState(true, android.app.GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE)
            gm.setGameState(state)
            Log.i(TAG, "✅ GameState → GAMEPLAY_UNINTERRUPTIBLE")
        } catch (e: Exception) { Log.w(TAG, "GameState: ${e.message}") }
    }

    fun reportLoadingStarted() {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            val gm = ctx.getSystemService(android.app.GameManager::class.java) ?: return
            val state = android.app.GameState(false, android.app.GameState.MODE_CONTENT)
            gm.setGameState(state)
            Log.i(TAG, "GameState → LOADING/CONTENT")
        } catch (e: Exception) { }
    }

    // ── ThermalStatusCallback (API 29+) ───────────────────────────────────────
    private var thermalListener: Any? = null

    fun startThermalMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                val label = when (status) {
                    PowerManager.THERMAL_STATUS_NONE       -> "🟢 Normal"
                    PowerManager.THERMAL_STATUS_LIGHT      -> "🟡 Light"
                    PowerManager.THERMAL_STATUS_MODERATE   -> "🟠 Moderate"
                    PowerManager.THERMAL_STATUS_SEVERE     -> "🔴 Severe"
                    PowerManager.THERMAL_STATUS_CRITICAL   -> "🔴🔴 CRITICAL"
                    PowerManager.THERMAL_STATUS_EMERGENCY  -> "🚨 EMERGENCY"
                    PowerManager.THERMAL_STATUS_SHUTDOWN   -> "💀 SHUTDOWN"
                    else -> "Unknown($status)"
                }
                Log.w(TAG, "🌡 Thermal: $label")
                onThermalStatus?.invoke(status, label)
            }
            pm.addThermalStatusListener(listener)
            thermalListener = listener
            Log.i(TAG, "✅ ThermalStatusCallback registered")
        } catch (e: Exception) { Log.w(TAG, "Thermal: ${e.message}") }
    }

    fun stopThermalMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val listener = thermalListener as? PowerManager.OnThermalStatusChangedListener ?: return
            pm.removeThermalStatusListener(listener)
            thermalListener = null
        } catch (e: Exception) { }
    }

    // ── AppStandby Bucket ─────────────────────────────────────────────────────
    fun getStandbyBucket(pkg: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "N/A"
        return try {
            val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            when (um.getAppStandbyBucket()) {
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE      -> "🟢 ACTIVE"
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "🟡 WORKING_SET"
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT    -> "🟠 FREQUENT"
                android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE        -> "🔴 RARE"
                else -> "RESTRICTED"
            }
        } catch (e: Exception) { "Unknown" }
    }
}
