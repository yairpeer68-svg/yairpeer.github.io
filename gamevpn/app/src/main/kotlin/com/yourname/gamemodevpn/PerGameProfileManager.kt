package com.yourname.gamemodevpn

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Per-game settings — each game gets its own optimized config:
 * CoD Mobile: DSCP-EF + aggressive RST + 60fps hints
 * PUBG Mobile: MTU 1400 + lower jitter threshold
 * Free Fire:   balanced mode, lower power consumption
 */
data class PerGameConfig(
    val packageName: String,
    val gameName: String,
    val mtu: Int = 1500,
    val targetFps: Int = 60,
    val spikeThreshold: Int = 80,
    val dscpEf: Boolean = true,
    val rstBurst: Boolean = true,
    val dnd: Boolean = true,
    val audioFx: Boolean = true,
    val killBg: Boolean = false,
    val preferredRegion: String = "Middle East",
    val eqPreset: String = "FPS"
)

class PerGameProfileManager(private val ctx: Context) {

    companion object {
        const val TAG = "PerGameProfile"

        val DEFAULTS = mapOf(
            "com.activision.callofduty.shooter" to PerGameConfig(
                packageName = "com.activision.callofduty.shooter",
                gameName = "Call of Duty Mobile",
                mtu = 1480, targetFps = 60, spikeThreshold = 50,
                dscpEf = true, rstBurst = true, dnd = true,
                audioFx = true, killBg = true, preferredRegion = "Middle East",
                eqPreset = "FPS"
            ),
            "com.tencent.ig" to PerGameConfig(
                packageName = "com.tencent.ig",
                gameName = "PUBG Mobile",
                mtu = 1400, targetFps = 60, spikeThreshold = 70,
                dscpEf = true, rstBurst = true, dnd = true,
                audioFx = true, killBg = false, preferredRegion = "Middle East",
                eqPreset = "FPS"
            ),
            "com.dts.freefireth" to PerGameConfig(
                packageName = "com.dts.freefireth",
                gameName = "Free Fire",
                mtu = 1500, targetFps = 60, spikeThreshold = 90,
                dscpEf = false, rstBurst = false, dnd = true,
                audioFx = false, killBg = false, preferredRegion = "Asia",
                eqPreset = "Balance"
            )
        )
    }

    private val prefs = ctx.getSharedPreferences("per_game_configs", Context.MODE_PRIVATE)

    fun getConfig(pkg: String): PerGameConfig {
        val saved = prefs.getString(pkg, null)
        if (saved != null) {
            return try {
                val j = JSONObject(saved)
                PerGameConfig(
                    packageName = pkg,
                    gameName = j.optString("gameName", pkg),
                    mtu = j.optInt("mtu", 1500),
                    targetFps = j.optInt("targetFps", 60),
                    spikeThreshold = j.optInt("spikeThreshold", 80),
                    dscpEf = j.optBoolean("dscpEf", true),
                    rstBurst = j.optBoolean("rstBurst", true),
                    dnd = j.optBoolean("dnd", true),
                    audioFx = j.optBoolean("audioFx", true),
                    killBg = j.optBoolean("killBg", false),
                    preferredRegion = j.optString("preferredRegion", "Middle East"),
                    eqPreset = j.optString("eqPreset", "FPS")
                )
            } catch (e: Exception) { DEFAULTS[pkg] ?: PerGameConfig(pkg, pkg) }
        }
        return DEFAULTS[pkg] ?: PerGameConfig(pkg, pkg)
    }

    fun save(config: PerGameConfig) {
        val j = JSONObject().apply {
            put("gameName", config.gameName); put("mtu", config.mtu)
            put("targetFps", config.targetFps); put("spikeThreshold", config.spikeThreshold)
            put("dscpEf", config.dscpEf); put("rstBurst", config.rstBurst)
            put("dnd", config.dnd); put("audioFx", config.audioFx)
            put("killBg", config.killBg); put("preferredRegion", config.preferredRegion)
            put("eqPreset", config.eqPreset)
        }
        prefs.edit().putString(config.packageName, j.toString()).apply()
        Log.i(TAG, "Saved config for ${config.gameName}")
    }

    fun applyToPrefs(pkg: String, globalPrefs: android.content.SharedPreferences) {
        val cfg = getConfig(pkg)
        globalPrefs.edit().apply {
            putInt("mtu", cfg.mtu); putInt("target_fps", cfg.targetFps)
            putInt("spike_threshold", cfg.spikeThreshold)
            putBoolean("qos_dscp", cfg.dscpEf); putBoolean("rst_burst", cfg.rstBurst)
            putBoolean("dnd", cfg.dnd); putBoolean("audio_eq", cfg.audioFx)
            putBoolean("kill_bg", cfg.killBg)
        }.apply()
        Log.i(TAG, "Applied per-game config for ${cfg.gameName}: MTU=${cfg.mtu} spike=${cfg.spikeThreshold}ms")
    }

    // ── Learned per-game adaptive data ────────────────────────────────────────

    fun saveLearnedPing(packageName: String, avgPing: Int) {
        val key = "learned_ping_${packageName.replace(".", "_")}"
        ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
            .edit().putInt(key, avgPing).apply()
    }

    fun getLearnedPing(packageName: String): Int {
        val key = "learned_ping_${packageName.replace(".", "_")}"
        return ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
            .getInt(key, -1)
    }

    fun getAdaptiveThresholdForGame(packageName: String): Int {
        val learned = getLearnedPing(packageName)
        return if (learned > 0) (learned * 1.5f).toInt().coerceIn(40, 200)
        else getConfig(packageName).spikeThreshold
    }

    fun getAllLearnedPings(): Map<String, Int> {
        val prefs = ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
        return prefs.all
            .filterKeys { it.startsWith("learned_ping_") }
            .mapKeys { it.key.removePrefix("learned_ping_").replace("_", ".") }
            .mapValues { (it.value as? Int) ?: 0 }
    }
}
