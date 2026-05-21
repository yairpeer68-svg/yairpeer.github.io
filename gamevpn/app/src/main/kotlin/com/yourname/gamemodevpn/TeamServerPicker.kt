package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Team-aware server picker.
 * Each team member shares their IP/location; this finds the server
 * that minimizes total latency across all players.
 *
 * Usage: collect teammate pings (e.g. via QR code share), then
 * call findOptimalServer() to get the region that's best for the whole team.
 */
object TeamServerPicker {

    private const val TAG = "TeamServerPicker"

    data class PlayerPing(val playerId: String, val serverPings: Map<String, Long>)
    data class TeamResult(val bestRegion: String, val avgTeamPing: Long, val perPlayerPings: Map<String, Long>)

    /** Share-code representation of this device's server pings */
    suspend fun generateMyPings(game: String): Map<String, Long> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, Long>()
        val servers = when (game) {
            "CoD" -> listOf(
                "Middle East" to ("cod-me.prod.activision.com" to 3074),
                "Europe"      to ("cod-eu.prod.activision.com" to 3074),
                "Asia"        to ("cod-as.prod.activision.com" to 3074),
                "US East"     to ("cod-use.prod.activision.com" to 3074)
            )
            else -> listOf(
                "Middle East" to ("prod-live-me.pubg.com" to 443),
                "Europe"      to ("prod-live-eu.pubg.com" to 443),
                "Asia"        to ("prod-live-as.pubg.com" to 443)
            )
        }
        servers.map { (region, hostPort) ->
            async {
                val ping = tcpPing(hostPort.first, hostPort.second)
                region to ping
            }
        }.awaitAll().forEach { (region, ping) ->
            result[region] = ping
        }
        Log.i(TAG, "My pings: $result")
        result
    }

    /** Encode pings as a compact share string (for QR code or clipboard) */
    fun encodePings(pings: Map<String, Long>): String =
        pings.entries.joinToString(";") { "${it.key}:${it.value}" }

    fun decodePings(code: String): Map<String, Long> = try {
        code.split(";").associate { entry ->
            val parts = entry.split(":")
            parts[0] to parts[1].toLong()
        }
    } catch (_: Exception) { emptyMap() }

    /**
     * Find the server region that minimizes average ping across all team members.
     * [teamPings]: map of playerId → (region → pingMs)
     */
    fun findOptimalServer(teamPings: List<PlayerPing>): TeamResult? {
        if (teamPings.isEmpty()) return null
        val allRegions = teamPings.flatMap { it.serverPings.keys }.toSet()
        val bestRegion = allRegions.minByOrNull { region ->
            teamPings.mapNotNull { player -> player.serverPings[region] }.average()
        } ?: return null

        val avgPing = teamPings.mapNotNull { it.serverPings[bestRegion] }.average().toLong()
        val perPlayer = teamPings.associate { it.playerId to (it.serverPings[bestRegion] ?: -1L) }

        Log.i(TAG, "Team optimal: $bestRegion avg=${avgPing}ms")
        return TeamResult(bestRegion, avgPing, perPlayer)
    }

    private fun tcpPing(host: String, port: Int): Long {
        val t0 = System.currentTimeMillis()
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 2000)
                System.currentTimeMillis() - t0
            }
        } catch (_: Exception) { 999L }
    }
}
