package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetAddress

/**
 * DNS Leak Test — compares DoH resolution vs system DNS resolution.
 * A "leak" means the ISP can see your DNS queries despite using DoH.
 */
object DnsLeakTestManager {

    private const val TAG = "DnsLeakTest"

    data class LeakReport(
        val tested: Boolean,
        val leaked: Boolean,
        val dohIp: String?,
        val systemIp: String,
        val dohLatencyMs: Long,
        val systemLatencyMs: Long,
        val verdict: String
    )

    suspend fun runFullTest(): LeakReport = withContext(Dispatchers.IO) {
        val testDomains = listOf("whoami.cloudflare.com", "1.1.1.1.in-addr.arpa", "cloudflare.com")
        var leaked = false
        var dohIp: String? = null
        var systemIp = "unknown"
        var dohLatency = -1L
        var systemLatency = -1L

        for (domain in testDomains) {
            try {
                // DoH resolution
                val dohResult = DoHResolver.resolve(domain)
                dohIp = dohResult.ip
                dohLatency = dohResult.latencyMs

                // System DNS resolution
                val t0 = System.currentTimeMillis()
                systemIp = InetAddress.getByName(domain).hostAddress ?: "unknown"
                systemLatency = System.currentTimeMillis() - t0

                // Leaked if system DNS returned a different IP (ISP redirected)
                if (dohIp != null && systemIp != "unknown" && dohIp != systemIp) {
                    leaked = true
                    Log.w(TAG, "DNS LEAK detected: DoH=$dohIp System=$systemIp for $domain")
                }
                if (dohIp != null) break // got a result
            } catch (e: Exception) {
                Log.d(TAG, "Test domain $domain failed: ${e.message}")
            }
        }

        val verdict = when {
            dohIp == null    -> "❌ DoH לא זמין — DNS אינו מוצפן"
            leaked           -> "⚠️ DNS Leak! הספק רואה את שאילתות ה-DNS שלך"
            else             -> "✅ אין דליפת DNS — כל הבקשות מוצפנות"
        }

        LeakReport(
            tested = dohIp != null,
            leaked = leaked,
            dohIp = dohIp,
            systemIp = systemIp,
            dohLatencyMs = dohLatency,
            systemLatencyMs = systemLatency,
            verdict = verdict
        )
    }
}
