package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class GameServer(
    val region: String,
    val host: String,
    val port: Int,
    val game: String,
    var pingMs: Long = -1L,
    var status: String = "בודק..."
)

object ServerPingScanner {

    private val SERVERS = listOf(
        // Call of Duty
        GameServer("Middle East 🇸🇦", "cod-me.prod.activision.com", 3074, "CoD"),
        GameServer("Europe 🇩🇪",      "cod-eu.prod.activision.com", 3074, "CoD"),
        GameServer("Asia 🇯🇵",        "cod-as.prod.activision.com", 3074, "CoD"),
        GameServer("US East 🇺🇸",     "cod-use.prod.activision.com",3074, "CoD"),
        // PUBG
        GameServer("Middle East 🇸🇦", "prod-live-me.pubg.com",      443,  "PUBG"),
        GameServer("Europe 🇩🇪",      "prod-live-eu.pubg.com",       443,  "PUBG"),
        GameServer("Asia 🇯🇵",        "prod-live-as.pubg.com",       443,  "PUBG"),
        // Free Fire
        GameServer("Middle East 🇸🇦", "sg2.ff.garena.com",           443,  "FF"),
        GameServer("Europe 🇩🇪",      "ams.ff.garena.com",           443,  "FF"),
    )

    interface ScanCallback {
        fun onServerResult(server: GameServer)
        fun onScanComplete(sorted: List<GameServer>)
    }

    fun scan(game: String, callback: ScanCallback) {
        val targets = SERVERS.filter { it.game == game || game == "all" }
        CoroutineScope(Dispatchers.IO).launch {
            val deferreds = targets.map { server ->
                async {
                    val ping = tcpPing(server.host, server.port)
                    server.pingMs = ping
                    server.status = when {
                        ping < 0   -> "❌ לא זמין"
                        ping < 50  -> "🟢 מעולה"
                        ping < 100 -> "🟡 טוב"
                        else       -> "🔴 איטי"
                    }
                    withContext(Dispatchers.Main) { callback.onServerResult(server) }
                    server
                }
            }
            deferreds.awaitAll()
            val sorted = targets.filter { it.pingMs > 0 }.sortedBy { it.pingMs }
            withContext(Dispatchers.Main) { callback.onScanComplete(sorted) }
        }
    }

    fun getBestServer(game: String): GameServer? {
        return SERVERS.filter { it.game == game && it.pingMs > 0 }.minByOrNull { it.pingMs }
    }

    private fun tcpPing(host: String, port: Int, timeoutMs: Int = 3000): Long {
        return try {
            val addr = InetAddress.getByName(host) // DNS resolve first
            val start = System.currentTimeMillis()
            Socket().use { sock ->
                sock.connect(InetSocketAddress(addr, port), timeoutMs)
            }
            System.currentTimeMillis() - start
        } catch (e: Exception) {
            Log.w("PingScanner", "$host:$port — ${e.message}")
            -1L
        }
    }
}
