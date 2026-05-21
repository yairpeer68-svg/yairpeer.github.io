package com.yourname.gamemodevpn

import android.content.Context
import org.json.JSONObject

data class GameProfile(
    val name: String,
    val dnd: Boolean = true,
    val audioFocus: Boolean = true,
    val killBg: Boolean = false,
    val rstBurst: Boolean = true,
    val thermal: Boolean = true,
    val spikeThreshold: Int = 80,
    val mtu: Int = 1500,
    val selectedGames: Set<String> = emptySet()
)

class ProfileManager(ctx: Context) {

    private val prefs = ctx.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    val defaults = listOf(
        GameProfile("🏆 תחרותי", dnd=true, audioFocus=true, killBg=true,  rstBurst=true, spikeThreshold=50,  mtu=1480),
        GameProfile("🎮 Casual",  dnd=true, audioFocus=false,killBg=false, rstBurst=false,spikeThreshold=100, mtu=1500),
        GameProfile("🔋 חיסכון",  dnd=false,audioFocus=false,killBg=false, rstBurst=false,spikeThreshold=150, mtu=1400)
    )

    fun save(profile: GameProfile) {
        val json = JSONObject().apply {
            put("name",            profile.name)
            put("dnd",             profile.dnd)
            put("audioFocus",      profile.audioFocus)
            put("killBg",          profile.killBg)
            put("rstBurst",        profile.rstBurst)
            put("thermal",         profile.thermal)
            put("spikeThreshold",  profile.spikeThreshold)
            put("mtu",             profile.mtu)
        }
        prefs.edit().putString(profile.name, json.toString()).apply()
    }

    fun load(name: String): GameProfile? {
        val str = prefs.getString(name, null) ?: return null
        return try {
            val j = JSONObject(str)
            GameProfile(
                name           = j.getString("name"),
                dnd            = j.optBoolean("dnd", true),
                audioFocus     = j.optBoolean("audioFocus", true),
                killBg         = j.optBoolean("killBg", false),
                rstBurst       = j.optBoolean("rstBurst", true),
                thermal        = j.optBoolean("thermal", true),
                spikeThreshold = j.optInt("spikeThreshold", 80),
                mtu            = j.optInt("mtu", 1500)
            )
        } catch (e: Exception) { null }
    }

    fun applyToPrefs(profile: GameProfile, gamePrefs: android.content.SharedPreferences) {
        gamePrefs.edit().apply {
            putBoolean("dnd",          profile.dnd)
            putBoolean("audio_focus",  profile.audioFocus)
            putBoolean("kill_bg",      profile.killBg)
            putBoolean("rst_burst",    profile.rstBurst)
            putBoolean("thermal",      profile.thermal)
            putInt("spike_threshold",  profile.spikeThreshold)
            putInt("mtu",              profile.mtu)
        }.apply()
    }

    fun getSaved(): List<String> = prefs.all.keys.toList()
}
