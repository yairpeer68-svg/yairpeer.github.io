package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*

object AutoServerSelector {

    private const val TAG = "AutoServerSelect"
    private val blockedDomains = mutableSetOf<String>()
    @Volatile private var bestServer: GameServer? = null

    data class SelectionResult(
        val best: GameServer?,
        val blocked: List<GameServer>,
        val allowed: List<GameServer>
    )

    fun selectAndApply(game: String, onDone: (SelectionResult) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "Scanning servers for $game...")
            val deferred = CompletableDeferred<SelectionResult>()

            ServerPingScanner.scan(game, object : ServerPingScanner.ScanCallback {
                override fun onServerResult(server: GameServer) {
                    Log.d(TAG, "${server.region}: ${server.pingMs}ms")
                }
                override fun onScanComplete(sorted: List<GameServer>) {
                    if (sorted.isEmpty()) {
                        deferred.complete(SelectionResult(null, emptyList(), emptyList()))
                        return
                    }
                    val best = sorted.first()
                    bestServer = best
                    val bestPing = best.pingMs
                    val blocked = sorted.filter { it.pingMs > bestPing * 2 || it.pingMs > 150 }
                    val allowed = sorted.filter { it !in blocked }

                    synchronized(blockedDomains) {
                        blockedDomains.clear()
                        blocked.forEach { s ->
                            blockedDomains.add(s.host.lowercase())
                            Log.i(TAG, "Blocking slow server: ${s.region} (${s.pingMs}ms)")
                        }
                    }
                    Log.i(TAG, "Best: ${best.region} (${best.pingMs}ms) | Blocked: ${blocked.size}")
                    deferred.complete(SelectionResult(best, blocked, allowed))
                }
            })

            val result = deferred.await()
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    fun getBlockedDomains(): Set<String> = synchronized(blockedDomains) { blockedDomains.toSet() }
    fun getBestServer(): GameServer? = bestServer
    fun clearBlocked() = synchronized(blockedDomains) { blockedDomains.clear() }
    fun shouldBlock(domain: String): Boolean = synchronized(blockedDomains) {
        blockedDomains.any { domain.contains(it) }
    }
}
