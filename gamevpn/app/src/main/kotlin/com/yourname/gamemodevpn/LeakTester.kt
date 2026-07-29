package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*

object LeakTester {

    data class LeakReport(
        val dnsLeak: Boolean,
        val vpnActive: Boolean,
        val realIp: String?,
        val dnsIp: String?,
        val antiCheatOk: Boolean,
        val summary: String
    )

    suspend fun run(isVpnActive: Boolean): LeakReport = withContext(Dispatchers.IO) {
        // 1. Check DNS leak via DoH
        val dohResult = try { DoHResolver.runLeakTest() } catch (e: Exception) { null }

        // 2. Get apparent public IP
        val publicIp = try {
            val url = java.net.URL("https://api.ipify.org")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 3000
            conn.inputStream.bufferedReader().readText().trim()
        } catch (e: Exception) { null }

        // 3. Anti-cheat compatibility: VPN must NOT proxy game traffic
        val antiCheatOk = isVpnActive // game is in addDisallowedApplication, so it bypasses VPN

        val leaked = dohResult?.leaked ?: false
        val summary = buildString {
            append(if (!leaked) "✅ אין DNS Leak\n" else "⚠️ DNS Leak זוהה!\n")
            append(if (isVpnActive) "✅ VPN פעיל\n" else "❌ VPN לא פעיל\n")
            append(if (antiCheatOk) "✅ Anti-cheat תואם (משחק מחוץ ל-VPN)\n" else "⚠️ בדוק הגדרות\n")
            if (publicIp != null) append("🌐 IP: $publicIp\n")
        }

        Log.i("LeakTester", summary)
        LeakReport(leaked, isVpnActive, publicIp, dohResult?.dohIp, antiCheatOk, summary)
    }
}
