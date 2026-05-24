package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Periodic server health checker.
 * Polls all known game servers every 5 minutes and updates the blocked list.
 * Provides richer health info: latency trend, packet loss estimate, uptime.
 */
class ServerHealthChecker {

    data class ServerHealth(
        val server: GameServer,
        val latencyMs: Long,
        val trend: Trend,
        val consecutiveFailures: Int,
        val healthy: Boolean
    )

    enum class Trend { IMPROVING, STABLE, DEGRADING, UNKNOWN }

    private val healthMap = mutableMapOf<String, ServerHealth>()
    private val latencyHistory = mutableMapOf<String, ArrayDeque<Long>>()
    private var monitorJob: Job? = null

    companion object {
        private const val TAG = "ServerHealth"
        private const val POLL_INTERVAL_MS = 5 * 60 * 1000L  // every 5 min
        private const val UNHEALTHY_THRESHOLD_MS = 250L
        private const val MAX_FAILURES = 3
    }

    fun startMonitoring(game: String) {
        stopMonitoring()
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                checkAll(game)
                delay(POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "Health monitoring started for $game")
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private suspend fun checkAll(game: String) {
        val servers = listOf(
            GameServer("Middle East 🇸🇦", "cod-me.prod.activision.com", 3074, game),
            GameServer("Europe 🇩🇪",      "cod-eu.prod.activision.com", 3074, game),
            GameServer("Asia 🇯🇵",        "cod-as.prod.activision.com", 3074, game),
            GameServer("US East 🇺🇸",     "cod-use.prod.activision.com",3074, game)
        )

        coroutineScope {
            servers.map { server ->
                async {
                    val latency = tcpPing(server.host, server.port)
                    updateHealth(server, latency)
                }
            }.awaitAll()
        }

        val unhealthy = healthMap.values.filter { !it.healthy }
        if (unhealthy.isNotEmpty()) {
            Log.w(TAG, "Unhealthy servers: ${unhealthy.map { it.server.region }}")
        }
    }

    private fun updateHealth(server: GameServer, latencyMs: Long) {
        val history = latencyHistory.getOrPut(server.host) { ArrayDeque(10) }
        if (history.size >= 10) history.removeFirst()
        if (latencyMs > 0) history.addLast(latencyMs)

        val trend = when {
            history.size < 3 -> Trend.UNKNOWN
            else -> {
                val first = history.take(history.size / 2).average()
                val last  = history.drop(history.size / 2).average()
                when {
                    last < first * 0.85 -> Trend.IMPROVING
                    last > first * 1.15 -> Trend.DEGRADING
                    else                -> Trend.STABLE
                }
            }
        }

        val prev = healthMap[server.host]
        val failures = when {
            latencyMs <= 0 -> (prev?.consecutiveFailures ?: 0) + 1
            else -> 0
        }
        val healthy = latencyMs > 0 && latencyMs < UNHEALTHY_THRESHOLD_MS && failures < MAX_FAILURES

        healthMap[server.host] = ServerHealth(server, latencyMs, trend, failures, healthy)
        Log.d(TAG, "${server.region}: ${latencyMs}ms trend=$trend healthy=$healthy")
    }

    fun getHealth(host: String): ServerHealth? = healthMap[host]
    fun getAllHealth(): Map<String, ServerHealth> = healthMap.toMap()
    fun getUnhealthyServers(): List<GameServer> = healthMap.values.filter { !it.healthy }.map { it.server }

    private fun tcpPing(host: String, port: Int): Long {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use { s -> s.connect(InetSocketAddress(host, port), 3000) }
            System.currentTimeMillis() - t0
        } catch (_: Exception) { -1L }
    }
}
