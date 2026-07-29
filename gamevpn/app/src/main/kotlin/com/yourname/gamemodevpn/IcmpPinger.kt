package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetAddress

/**
 * Real ICMP ping using InetAddress.isReachable()
 * More accurate than TCP ping — measures actual network RTT
 */
object IcmpPinger {

    private const val TAG = "IcmpPinger"

    data class PingResult(
        val host: String,
        val pingMs: Long,
        val reachable: Boolean,
        val ttl: Int = 64
    )

    suspend fun ping(host: String, timeoutMs: Int = 3000): PingResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val addr = InetAddress.getByName(host)
            val t0 = System.currentTimeMillis()
            val reachable = addr.isReachable(timeoutMs)
            val latency = System.currentTimeMillis() - t0
            Log.d(TAG, "ICMP $host: ${if (reachable) "${latency}ms" else "unreachable"}")
            PingResult(host, if (reachable) latency else -1L, reachable)
        } catch (e: Exception) {
            Log.w(TAG, "ICMP error for $host: ${e.message}")
            PingResult(host, -1L, false)
        }
    }

    suspend fun pingMultiple(hosts: List<String>, count: Int = 5): Map<String, List<Long>> =
        withContext(Dispatchers.IO) {
            val results = mutableMapOf<String, MutableList<Long>>()
            hosts.forEach { host ->
                results[host] = mutableListOf()
                repeat(count) {
                    val r = ping(host)
                    if (r.reachable) results[host]!!.add(r.pingMs)
                    delay(200)
                }
            }
            results
        }

    fun calcStats(pings: List<Long>): Triple<Long, Long, Long> {  // min, avg, max
        if (pings.isEmpty()) return Triple(-1L, -1L, -1L)
        return Triple(pings.minOrNull() ?: -1L, pings.average().toLong(), pings.maxOrNull() ?: -1L)
    }

    suspend fun pingGameServers(game: String): List<PingResult> = withContext(Dispatchers.IO) {
        val hosts = when (game) {
            "CoD Mobile"  -> listOf("cod-me.prod.activision.com", "cod-eu.prod.activision.com")
            "PUBG Mobile" -> listOf("prod-live-me.pubg.com", "prod-live-eu.pubg.com")
            else          -> listOf("8.8.8.8", "1.1.1.1")
        }
        coroutineScope { hosts.map { async { ping(it) } }.awaitAll() }
    }
}
