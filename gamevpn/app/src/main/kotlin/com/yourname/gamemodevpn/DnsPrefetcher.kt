package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetAddress

/**
 * Pre-resolves game server hostnames before the game launches.
 * Warms up DNS cache → eliminates initial connection latency.
 */
object DnsPrefetcher {

    private val GAME_SERVERS = mapOf(
        "CoD Mobile"  to listOf(
            "cod-me.prod.activision.com",
            "prod.uno.call.activision.com",
            "login.activision.com",
            "matchmaking.activision.com"
        ),
        "PUBG Mobile" to listOf(
            "prod-live-me.pubg.com",
            "prod-live-as.pubg.com",
            "login.pubg.com"
        ),
        "Free Fire"   to listOf(
            "sg2.ff.garena.com",
            "freefire.garena.com"
        )
    )

    data class PrefetchResult(val host: String, val ip: String?, val latencyMs: Long)

    private val cache = mutableMapOf<String, PrefetchResult>()

    fun prefetch(game: String, onResult: (PrefetchResult) -> Unit = {}) {
        val hosts = GAME_SERVERS[game] ?: GAME_SERVERS.values.flatten()
        CoroutineScope(Dispatchers.IO).launch {
            hosts.map { host ->
                async {
                    val t0 = System.currentTimeMillis()
                    val ip = try {
                        InetAddress.getByName(host).hostAddress
                    } catch (e: Exception) { null }
                    val result = PrefetchResult(host, ip, System.currentTimeMillis() - t0)
                    cache[host] = result
                    Log.i("DnsPrefetch", "$host → $ip (${result.latencyMs}ms)")
                    withContext(Dispatchers.Main) { onResult(result) }
                    result
                }
            }.awaitAll()
            Log.i("DnsPrefetch", "✅ Prefetched ${hosts.size} hosts for $game")
        }
    }

    fun getCached(host: String) = cache[host]
    fun getAllCached() = cache.values.toList()
    fun clearCache() = cache.clear()
}
