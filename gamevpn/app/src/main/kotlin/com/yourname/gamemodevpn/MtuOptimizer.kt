package com.yourname.gamemodevpn

import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object MtuOptimizer {
    // Probe sizes to test (bytes) - standard MTU values
    private val PROBE_SIZES = listOf(1500, 1480, 1452, 1400, 1350, 1280)
    private const val TARGET = "1.1.1.1"
    private const val PORT = 53
    private const val TIMEOUT = 2000

    data class MtuResult(val mtu: Int, val works: Boolean, val latencyMs: Long)

    interface Callback {
        fun onResult(result: MtuResult)
        fun onBestFound(bestMtu: Int)
    }

    fun probe(callback: Callback) {
        CoroutineScope(Dispatchers.IO).launch {
            var best = 1280
            for (size in PROBE_SIZES) {
                val result = probeMtu(size)
                withContext(Dispatchers.Main) { callback.onResult(result) }
                if (result.works) { best = size; break }
                delay(100)
            }
            withContext(Dispatchers.Main) { callback.onBestFound(best) }
            Log.i("MtuOptimizer", "Best MTU: $best")
        }
    }

    private fun probeMtu(size: Int): MtuResult {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = TIMEOUT
            // Build payload of given size minus UDP+IP headers (28 bytes)
            val payloadSize = (size - 28).coerceAtLeast(8)
            val data = ByteArray(payloadSize)
            val addr = InetAddress.getByName(TARGET)
            val pkt = DatagramPacket(data, data.size, addr, PORT)
            val t0 = System.currentTimeMillis()
            socket.send(pkt)
            val latency = System.currentTimeMillis() - t0
            socket.close()
            MtuResult(size, true, latency)
        } catch (e: Exception) {
            MtuResult(size, false, -1L)
        }
    }

}
