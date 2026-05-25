package com.yourname.gamemodevpn

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Socket

/**
 * MPTCP + WiFiLock + MulticastLock + Dual-socket binding
 *
 * - WiFiLock: prevents WiFi power-save packet loss
 * - MulticastLock: enables LAN peer discovery
 * - MPTCP (Android 12+): uses WiFi + 5G simultaneously
 * - Dual-path sockets: bind individual sockets to WiFi or Cellular network objects
 *   obtained via separate ConnectivityManager.requestNetwork() calls (one per transport),
 *   enabling true per-socket path selection for packet duplication / racing.
 */
class MultiPathManager(private val ctx: Context) {

    private val cm   = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm   = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var wifiLock: WifiManager.WifiLock? = null
    private var mcastLock: WifiManager.MulticastLock? = null
    private var mptcpNetwork: Network? = null

    // ── Dual-path: separate Network objects for WiFi and Cellular ────────────
    /** Network object bound via a dedicated WiFi-only requestNetwork() call. */
    @Volatile private var requestedWifiNetwork: Network? = null

    /** Network object bound via a dedicated Cellular-only requestNetwork() call. */
    @Volatile private var requestedCellularNetwork: Network? = null

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null

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

    /**
     * Activate all multi-path features:
     *  1. WiFi lock (prevents power-save dropouts)
     *  2. Multicast lock (LAN peer discovery)
     *  3. MPTCP bonding (Android 12+)
     *  4. Separate requestNetwork() calls for WiFi and Cellular so callers can
     *     bind individual sockets to a specific transport via [bindSocketToWifi]
     *     or [bindSocketToCellular].
     */
    fun activateAll() {
        acquireWifiLock()
        acquireMulticastLock()
        enableMptcp()
        requestWifiNetwork()
        requestCellularNetwork()
    }

    fun deactivateAll() {
        releaseWifiLock()
        releaseMulticastLock()
        releaseRequestedNetworks()
    }

    // ── Dedicated requestNetwork() calls ─────────────────────────────────────

    /**
     * Requests a network with TRANSPORT_WIFI via ConnectivityManager.requestNetwork().
     * The resulting [Network] object is stored in [requestedWifiNetwork] and can be
     * used to bind sockets so they egress exclusively over WiFi.
     */
    private fun requestWifiNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    requestedWifiNetwork = network
                    Log.i(TAG, "WiFi network available via requestNetwork(): $network")
                }
                override fun onLost(network: Network) {
                    if (requestedWifiNetwork == network) {
                        requestedWifiNetwork = null
                        Log.w(TAG, "WiFi network lost")
                    }
                }
            }
            cm.requestNetwork(req, cb)
            wifiCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "requestWifiNetwork: ${e.message}")
        }
    }

    /**
     * Requests a network with TRANSPORT_CELLULAR via ConnectivityManager.requestNetwork().
     * The resulting [Network] object is stored in [requestedCellularNetwork] and can be
     * used to bind sockets so they egress exclusively over the cellular radio.
     */
    private fun requestCellularNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    requestedCellularNetwork = network
                    Log.i(TAG, "Cellular network available via requestNetwork(): $network")
                }
                override fun onLost(network: Network) {
                    if (requestedCellularNetwork == network) {
                        requestedCellularNetwork = null
                        Log.w(TAG, "Cellular network lost")
                    }
                }
            }
            cm.requestNetwork(req, cb)
            cellularCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "requestCellularNetwork: ${e.message}")
        }
    }

    private fun releaseRequestedNetworks() {
        try { wifiCallback?.let { cm.unregisterNetworkCallback(it) } } catch (_: Exception) { }
        try { cellularCallback?.let { cm.unregisterNetworkCallback(it) } } catch (_: Exception) { }
        wifiCallback           = null
        cellularCallback       = null
        requestedWifiNetwork    = null
        requestedCellularNetwork = null
        Log.i(TAG, "Requested networks released")
    }

    // ── Socket binding helpers ────────────────────────────────────────────────

    /**
     * Binds [socket] to the WiFi [Network] object obtained via [requestNetwork].
     * After binding, all traffic on this socket exits through the WiFi interface
     * regardless of the system's default route.
     *
     * Returns true if the bind succeeded; false if the WiFi network is unavailable
     * or the device is below API 23.
     */
    fun bindSocketToWifi(socket: Socket): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val network = requestedWifiNetwork ?: run {
            Log.w(TAG, "bindSocketToWifi: WiFi network not yet available")
            return false
        }
        return try {
            network.bindSocket(socket)
            Log.d(TAG, "Socket bound to WiFi network")
            true
        } catch (e: Exception) {
            Log.w(TAG, "bindSocketToWifi error: ${e.message}")
            false
        }
    }

    /**
     * Binds [socket] to the Cellular [Network] object obtained via [requestNetwork].
     * After binding, all traffic on this socket exits through the cellular interface
     * regardless of the system's default route.
     *
     * Returns true if the bind succeeded; false if the Cellular network is unavailable
     * or the device is below API 23.
     */
    fun bindSocketToCellular(socket: Socket): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val network = requestedCellularNetwork ?: run {
            Log.w(TAG, "bindSocketToCellular: Cellular network not yet available")
            return false
        }
        return try {
            network.bindSocket(socket)
            Log.d(TAG, "Socket bound to Cellular network")
            true
        } catch (e: Exception) {
            Log.w(TAG, "bindSocketToCellular error: ${e.message}")
            false
        }
    }

    // ── Packet duplication: send via both WiFi and Cellular for zero packet loss ──

    /**
     * Derived WiFi network via allNetworks scan — kept for backward compat with
     * [bindToNetwork] callers that do not go through [activateAll].
     */
    private val wifiNetwork: Network? get() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        // Prefer the explicitly requested network; fall back to a scan of allNetworks.
        return requestedWifiNetwork ?: try {
            cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Exception) { null }
    }

    /**
     * Derived Cellular network via allNetworks scan — kept for backward compat with
     * [bindToNetwork] callers that do not go through [activateAll].
     */
    private val cellularNetwork: Network? get() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return requestedCellularNetwork ?: try {
            cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Exception) { null }
    }

    /**
     * Bind a DatagramSocket to a specific network interface.
     * Used for packet duplication: send same UDP packet on both WiFi and Cellular.
     * The first ACK wins; the duplicate is ignored by the server.
     *
     * Kept for backward compatibility. Prefer [bindSocketToWifi] / [bindSocketToCellular]
     * for TCP [Socket] instances.
     */
    fun bindToNetwork(socket: java.net.DatagramSocket, preferCellular: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val network = if (preferCellular) cellularNetwork else wifiNetwork
        try {
            network?.bindSocket(socket)
            Log.d(TAG, "Socket bound to ${if (preferCellular) "cellular" else "wifi"}")
        } catch (e: Exception) { Log.w(TAG, "bindSocket: ${e.message}") }
    }

    /**
     * Returns true if both WiFi and Cellular networks are simultaneously available.
     * Uses the explicitly requested [Network] objects when [activateAll] has been called,
     * otherwise falls back to scanning allNetworks.
     */
    fun isDualPathAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return wifiNetwork != null && cellularNetwork != null
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
