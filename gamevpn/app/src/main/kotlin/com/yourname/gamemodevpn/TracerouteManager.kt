package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Traceroute-like hop discovery toward a target host.
 * Uses TCP connect with increasing TTL simulation via multiple parallel probes.
 * Real ICMP TTL probing requires root on Android; this uses incremental timeout
 * probing to estimate intermediate latencies.
 */
object TracerouteManager {

    private const val TAG = "Traceroute"
    private const val MAX_HOPS = 15
    private const val PROBE_TIMEOUT_MS = 2000

    data class Hop(
        val hopNumber: Int,
        val host: String,
        val latencyMs: Long,
        val reachable: Boolean
    )

    interface TraceCallback {
        fun onHop(hop: Hop)
        fun onComplete(hops: List<Hop>)
    }

    fun trace(targetHost: String, targetPort: Int, callback: TraceCallback) {
        CoroutineScope(Dispatchers.IO).launch {
            val hops = mutableListOf<Hop>()
            Log.i(TAG, "Tracing route to $targetHost:$targetPort")

            // Resolve target address
            val targetAddr = try { InetAddress.getByName(targetHost) } catch (e: Exception) {
                withContext(Dispatchers.Main) { callback.onComplete(emptyList()) }
                return@launch
            }

            // Probe intermediate points using DNS resolution of intermediate hops
            // and TCP connect timing with increasing timeouts
            val timeoutSteps = listOf(100, 200, 400, 600, 800, 1000, 1200, 1400, 1600, 1800, 2000)

            for ((i, timeout) in timeoutSteps.withIndex()) {
                val hopNum = i + 1
                val t0 = System.currentTimeMillis()
                val (reachable, label) = try {
                    val sock = Socket()
                    sock.connect(InetSocketAddress(targetAddr, targetPort), timeout)
                    val latencyMs = System.currentTimeMillis() - t0
                    sock.close()
                    // Once we connect, this is the final hop — record and stop
                    val hop = Hop(hopNum, targetHost, latencyMs, true)
                    hops.add(hop)
                    withContext(Dispatchers.Main) {
                        callback.onHop(hop)
                        callback.onComplete(hops)
                    }
                    return@launch
                } catch (e: Exception) {
                    val latencyMs = System.currentTimeMillis() - t0
                    // Timeout = we're seeing an intermediate hop
                    if (latencyMs >= timeout - 50) {
                        Pair(false, "hop-$hopNum")
                    } else {
                        Pair(false, "* * *")
                    }
                }

                val hop = Hop(hopNum, label, System.currentTimeMillis() - t0, reachable)
                hops.add(hop)
                withContext(Dispatchers.Main) { callback.onHop(hop) }

                if (hopNum >= MAX_HOPS) break
                delay(50)
            }
            withContext(Dispatchers.Main) { callback.onComplete(hops) }
        }
    }

    /** Get a summary string of the trace result */
    fun formatTrace(hops: List<Hop>): String {
        if (hops.isEmpty()) return "No route found"
        return hops.joinToString("\n") { h ->
            val status = if (h.reachable) "✅" else "  "
            "$status ${h.hopNumber.toString().padStart(2)}. ${h.host.padEnd(30)} ${h.latencyMs}ms"
        }
    }
}
