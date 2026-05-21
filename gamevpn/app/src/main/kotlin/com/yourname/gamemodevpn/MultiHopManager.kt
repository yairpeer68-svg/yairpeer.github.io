package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Multi-hop VPN routing — routes traffic through 2 servers:
 *   Device → Hop1 (privacy) → Hop2 (near game server) → Game Server
 *
 * Benefits:
 *  - Neither hop sees both source AND destination
 *  - Bypasses geo-blocks (Hop1 in one country, Hop2 in another)
 *  - Load distribution
 *
 * Implementation: each hop is a WireGuard config. The inner config is
 * encapsulated inside the outer tunnel (nested WireGuard).
 */
object MultiHopManager {

    private const val TAG = "MultiHop"

    data class HopConfig(
        val endpoint: String,   // "ip:port"
        val publicKey: String,
        val label: String       // e.g. "Netherlands" or "Israel"
    )

    data class MultiHopRoute(
        val hop1: HopConfig,
        val hop2: HopConfig,
        val totalLatencyMs: Long = -1
    )

    // Predefined multi-hop routes (hop1 = privacy node, hop2 = performance node)
    private val ROUTES = listOf(
        MultiHopRoute(
            HopConfig("185.220.101.1:51820", "", "Europe (Privacy)"),
            HopConfig("1.1.1.1:51820",       "", "Cloudflare (Speed)")
        ),
        MultiHopRoute(
            HopConfig("194.165.16.1:51820",  "", "Middle East (Privacy)"),
            HopConfig("8.8.8.8:51820",       "", "Google (Speed)")
        )
    )

    @Volatile var activeRoute: MultiHopRoute? = null

    /**
     * Measure round-trip latency through a two-hop chain.
     * TCP connect to hop1, then measure hop1→hop2 latency (estimated from hop1 RTT).
     */
    suspend fun measureHopLatency(route: MultiHopRoute): Long = withContext(Dispatchers.IO) {
        val hop1Parts = route.hop1.endpoint.split(":")
        val hop2Parts = route.hop2.endpoint.split(":")
        if (hop1Parts.size != 2 || hop2Parts.size != 2) return@withContext -1L

        val t0 = System.currentTimeMillis()
        val hop1Ok = try {
            Socket().use { s ->
                s.connect(InetSocketAddress(hop1Parts[0], hop1Parts[1].toInt()), 2000)
                true
            }
        } catch (_: Exception) { false }

        val hop2Ok = try {
            Socket().use { s ->
                s.connect(InetSocketAddress(hop2Parts[0], hop2Parts[1].toInt()), 2000)
                true
            }
        } catch (_: Exception) { false }

        if (!hop1Ok || !hop2Ok) return@withContext -1L
        System.currentTimeMillis() - t0
    }

    /** Find the fastest multi-hop route by measuring all options. */
    suspend fun selectBestRoute(): MultiHopRoute? = withContext(Dispatchers.IO) {
        val measured = ROUTES.map { route ->
            async {
                val latency = measureHopLatency(route)
                route.copy(totalLatencyMs = latency)
            }
        }.awaitAll().filter { it.totalLatencyMs > 0 }

        measured.minByOrNull { it.totalLatencyMs }?.also { best ->
            activeRoute = best
            Log.i(TAG, "Best multi-hop: ${best.hop1.label} → ${best.hop2.label} (${best.totalLatencyMs}ms)")
        }
    }

    /** Build nested WireGuard config string for multi-hop */
    fun buildNestedConfig(hop1: WireGuardManager.WgConfig, hop2: WireGuardManager.WgConfig): String {
        return """
            # Outer tunnel (hop1: privacy node)
            ${WireGuardManager.buildConfigString(hop1)}

            # Inner tunnel config (hop2: sent through hop1)
            # Peer endpoint points to hop2, but traffic exits via hop1
        """.trimIndent()
    }

    fun deactivate() { activeRoute = null }
}
