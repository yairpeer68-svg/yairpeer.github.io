package com.yourname.gamemodevpn

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

data class DetectedGame(val packageName: String, val name: String, val confidence: Float)

object GameAutoDetector {

    // Known game publishers/packages
    private val GAME_PUBLISHERS = setOf(
        "com.activision", "com.tencent", "com.pubg", "com.garena",
        "com.mojang", "com.ea.", "com.supercell", "com.king.",
        "com.ubisoft", "com.netease", "com.gameloft", "com.rovio",
        "com.mihoyo", "io.supercent", "com.nianticlabs"
    )
    private val GAME_KEYWORDS = listOf(
        "game", "shooter", "battle", "royal", "war", "call", "duty",
        "pubg", "fortnite", "fire", "legend", "arena", "mobile", "online",
        "fps", "rpg", "moba", "racing", "sport", "fight", "survival"
    )

    fun detect(ctx: Context): List<DetectedGame> {
        val pm = ctx.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val games = mutableListOf<DetectedGame>()

        for (app in installed) {
            if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
            val pkg = app.packageName.lowercase()
            val name = pm.getApplicationLabel(app).toString().lowercase()
            var confidence = 0f

            // Publisher match
            if (GAME_PUBLISHERS.any { pkg.startsWith(it) }) confidence += 0.6f

            // Keyword match in package name or app name
            val matches = GAME_KEYWORDS.count { pkg.contains(it) || name.contains(it) }
            confidence += matches * 0.15f

            // Has launch intent (is an app, not a service)
            if (pm.getLaunchIntentForPackage(app.packageName) == null) continue

            // Large APK = likely game (>50MB)
            try {
                val apkSize = java.io.File(app.sourceDir).length()
                if (apkSize > 50_000_000) confidence += 0.2f
            } catch (e: Exception) { }

            if (confidence >= 0.4f) {
                games.add(DetectedGame(
                    app.packageName,
                    pm.getApplicationLabel(app).toString(),
                    confidence.coerceAtMost(1f)
                ))
            }
        }

        val result = games.sortedByDescending { it.confidence }
        Log.i("AutoDetect", "Found ${result.size} likely games")
        return result
    }
}
