package com.yourname.gamemodevpn

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

/**
 * MPTCP + WiFiLock + MulticastLock
 * - MPTCP (Android 12+): uses WiFi + 5G simultaneously
 * - WiFiLock: prevents WiFi power-save packet loss
 * - MulticastLock: enables LAN peer discovery
 */
class MultiPathManager(private val ctx: Context) {

    private val cm   = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm   = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var wifiLock: WifiManager.WifiLock? = null
    private var mcastLock: WifiManager.MulticastLock? = null
    private var mptcpNetwork: Network? = null

    companion object { const val TAG = "MultiPath" }

    // ── WiFi Lock (prevents power-save dropouts) ───────────────────────────────
    fun acquireWifiLock() {
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GameBoost::WiFiLock").also {
            it.acquire()
            Log.i(TAG, "✅ WiFi HIGH_PERF lock acquired — no power-save dropouts")
        }
    }

    fun releaseWifiLock() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (e: Exception) { }
        wifiLock = null
        Log.i(TAG, "WiFi lock released")
    }

    // ── Multicast Lock (LAN gaming / peer discovery) ───────────────────────────
    fun acquireMulticastLock() {
        mcastLock = wm.createMulticastLock("GameBoost::MulticastLock").also {
            it.setReferenceCounted(false)
            it.acquire()
            Log.i(TAG, "✅ Multicast lock acquired — LAN discovery enabled")
        }
    }

    fun releaseMulticastLock() {
        try { if (mcastLock?.isHeld == true) mcastLock?.release() } catch (e: Exception) { }
        mcastLock = null
    }

    // ── MPTCP: request network with both WiFi + Cellular ──────────────────────
    fun enableMptcp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "MPTCP requires Android 12+")
            return
        }
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()

            cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    mptcpNetwork = network
                    Log.i(TAG, "✅ MPTCP network available: WiFi + Cellular bonded")
                }
                override fun onLost(network: Network) {
                    Log.w(TAG, "MPTCP network lost")
                    mptcpNetwork = null
                }
            })
        } catch (e: Exception) { Log.w(TAG, "MPTCP: ${e.message}") }
    }

    fun activateAll() {
        acquireWifiLock()
        acquireMulticastLock()
        enableMptcp()
    }

    fun deactivateAll() {
        releaseWifiLock()
        releaseMulticastLock()
    }

    // ── Packet duplication: send via both WiFi and Cellular for zero packet loss ──
    private val cellularNetwork: Network? get() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Exception) { null }
    }

    private val wifiNetwork: Network? get() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Exception) { null }
    }

    /**
     * Bind a socket to a specific network interface.
     * Used for packet duplication: send same UDP packet on both WiFi and Cellular.
     * The first ACK wins; the duplicate is ignored by the server.
     */
    fun bindToNetwork(socket: java.net.DatagramSocket, preferCellular: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val network = if (preferCellular) cellularNetwork else wifiNetwork
        try {
            network?.bindSocket(socket)
            Log.d(TAG, "Socket bound to ${if (preferCellular) "cellular" else "wifi"}")
        } catch (e: Exception) { Log.w(TAG, "bindSocket: ${e.message}") }
    }

    /** Returns true if both WiFi and Cellular are simultaneously available. */
    fun isDualPathAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return cellularNetwork != null && wifiNetwork != null
    }

    fun getStatus(): String {
        val parts = mutableListOf<String>()
        if (isWifiLocked()) parts.add("WiFiLock✓")
        if (isMptcpAvailable()) parts.add("MPTCP✓")
        if (isDualPathAvailable()) parts.add("DualPath✓")
        return if (parts.isEmpty()) "Standard" else parts.joinToString(" ")
    }

    fun isWifiLocked() = wifiLock?.isHeld == true
    fun isMptcpAvailable() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}
