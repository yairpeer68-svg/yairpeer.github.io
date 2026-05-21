package com.yourname.gamemodevpn

import android.util.Log
import java.io.File
import java.net.InetAddress

data class TcpConnection(
    val localIp: String, val localPort: Int,
    val remoteIp: String, val remotePort: Int,
    val state: Int // 1=ESTABLISHED, 6=TIME_WAIT etc
)

object ProcTcpReader {
    // /proc/net/tcp format: local_addr rem_addr state ...
    // Addresses are hex, little-endian per byte

    fun getEstablished(): List<TcpConnection> {
        val conns = mutableListOf<TcpConnection>()
        try {
            File("/proc/net/tcp").forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 4 || parts[0] == "sl") return@forEachLine
                val local  = parseAddr(parts[1])
                val remote = parseAddr(parts[2])
                val state  = parts[3].toIntOrNull(16) ?: return@forEachLine
                if (state == 1 && local != null && remote != null) // ESTABLISHED only
                    conns.add(TcpConnection(local.first, local.second, remote.first, remote.second, state))
            }
            // Also check tcp6
            File("/proc/net/tcp6").forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 4 || parts[0] == "sl") return@forEachLine
                val state = parts[3].toIntOrNull(16) ?: return@forEachLine
                if (state == 1) {
                    val local  = parseAddr6(parts[1])
                    val remote = parseAddr6(parts[2])
                    if (local != null && remote != null)
                        conns.add(TcpConnection(local.first, local.second, remote.first, remote.second, state))
                }
            }
        } catch (e: Exception) {
            Log.w("ProcTcpReader", "Cannot read /proc/net/tcp: ${e.message}")
        }
        return conns
    }

    // Filter connections NOT belonging to the game processes
    fun getBackgroundConnections(gamePackages: Set<String>): List<TcpConnection> {
        val all = getEstablished()
        // Simple heuristic: exclude loopback and VPN subnet
        return all.filter { c ->
            !c.remoteIp.startsWith("127.") &&
            !c.remoteIp.startsWith("10.0.0") &&
            !c.remoteIp.startsWith("0.0.0")
        }
    }

    fun logConnections() {
        val conns = getEstablished()
        Log.i("ProcTcpReader", "Open TCP connections: ${conns.size}")
        conns.take(10).forEach { Log.d("ProcTcpReader", "  ${it.localIp}:${it.localPort} → ${it.remoteIp}:${it.remotePort}") }
    }

    private fun parseAddr(hex: String): Pair<String, Int>? {
        return try {
            val parts = hex.split(":")
            if (parts.size != 2) return null
            val ipHex  = parts[0]
            val portHex = parts[1]
            // IP: little-endian 4 bytes
            val ip = (0..3).map { i ->
                ipHex.substring(i * 2, i * 2 + 2).toInt(16)
            }.reversed().joinToString(".")
            val port = portHex.toInt(16)
            Pair(ip, port)
        } catch (e: Exception) { null }
    }

    private fun parseAddr6(hex: String): Pair<String, Int>? {
        return try {
            val parts = hex.split(":")
            if (parts.size != 2) return null
            Pair("ipv6:${parts[0].take(8)}", parts[1].toInt(16))
        } catch (e: Exception) { null }
    }
}
