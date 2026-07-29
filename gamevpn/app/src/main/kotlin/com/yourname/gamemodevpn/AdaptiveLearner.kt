package com.yourname.gamemodevpn

import android.content.Context
import android.util.Log
import java.util.Calendar

/**
 * ML-lite adaptive learning:
 * 1. Adaptive spike threshold — learns your typical ping per hour
 * 2. Auto-profile selection — picks best profile for current context
 * 3. Network quality score — weighted composite 0-100
 */
class AdaptiveLearner(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("adaptive", Context.MODE_PRIVATE)
    private val db = SessionDatabase(ctx)

    companion object { const val TAG = "AdaptiveLearner" }

    // ── Adaptive spike threshold ───────────────────────────────────────────────
    fun getAdaptiveThreshold(): Int {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val sessions = db.getLast(200)
        val hourSessions = sessions.filter {
            val cal = Calendar.getInstance().also { c -> c.timeInMillis = it.startTime }
            cal.get(Calendar.HOUR_OF_DAY) == hour
        }

        if (hourSessions.isEmpty()) return prefs.getInt("global_threshold", 80)

        // Use mean + 1.5σ as threshold (statistical outlier detection)
        val pings = hourSessions.map { it.avgPing.toFloat() }
        val mean = pings.average().toFloat()
        val std  = kotlin.math.sqrt(pings.map { (it - mean) * (it - mean) }.average()).toFloat()
        val adaptive = (mean + 1.5f * std).toInt().coerceIn(30, 200)

        Log.i(TAG, "Adaptive threshold for hour $hour: ${adaptive}ms (mean=${mean.toInt()}, std=${std.toInt()})")
        prefs.edit().putInt("hour_threshold_$hour", adaptive).apply()
        return adaptive
    }

    // ── Auto-profile selection ─────────────────────────────────────────────────
    fun selectBestProfile(
        gamePkg: String,
        batteryPct: Int,
        isCharging: Boolean,
        hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): GameProfile {
        val profileMgr = ProfileManager(ctx)
        val sessions = db.getLast(50)

        // Low battery → save mode (index 2 = save profile)
        if (batteryPct < 20 && !isCharging) {
            Log.i(TAG, "Auto-profile: SAVE (low battery)")
            return profileMgr.defaults.getOrElse(2) { profileMgr.defaults.last() }
        }

        // Charging → max performance (index 0 = competitive profile)
        if (isCharging) {
            Log.i(TAG, "Auto-profile: COMPETITIVE (charging)")
            return profileMgr.defaults.first()
        }

        // Analyze hour history
        val hourSessions = sessions.filter {
            val cal = Calendar.getInstance().also { c -> c.timeInMillis = it.startTime }
            cal.get(Calendar.HOUR_OF_DAY) == hour
        }
        val avgPing = if (hourSessions.isNotEmpty()) hourSessions.map { it.avgPing }.average() else 999.0

        return when {
            avgPing < 40 -> { Log.i(TAG,"Auto: CASUAL (good network hour)"); profileMgr.defaults.getOrElse(1) { profileMgr.defaults.first() } }
            else         -> { Log.i(TAG,"Auto: COMPETITIVE (need boost)");    profileMgr.defaults.first() }
        }
    }

    // ── Network quality score 0-100 ────────────────────────────────────────────
    fun computeScore(ping: Int, lossPercent: Float, jitter: Int, tempC: Float): Int {
        // Weighted formula: ping 40%, loss 35%, jitter 15%, temp 10%
        val pingScore    = (1f - (ping / 200f).coerceIn(0f, 1f)) * 40f
        val lossScore    = (1f - (lossPercent / 20f).coerceIn(0f, 1f)) * 35f
        val jitterScore  = (1f - (jitter / 100f).coerceIn(0f, 1f)) * 15f
        val tempScore    = if (tempC > 0) ((1f - ((tempC - 30f) / 20f).coerceIn(0f, 1f)) * 10f) else 10f
        val total = (pingScore + lossScore + jitterScore + tempScore).toInt().coerceIn(0, 100)
        prefs.edit().putInt("last_score", total).apply()
        return total
    }

    fun getScoreLabel(score: Int) = when {
        score >= 85 -> "🟢 מצוין ($score)"
        score >= 65 -> "🟡 טוב ($score)"
        score >= 45 -> "🟠 בינוני ($score)"
        else        -> "🔴 גרוע ($score)"
    }

    fun saveLearnedData(ping: Int, temp: Float) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val key = "hour_avg_$hour"
        val prev = prefs.getFloat(key, ping.toFloat())
        prefs.edit().putFloat(key, prev * 0.8f + ping * 0.2f).apply() // EMA update
    }
}
