package com.yourname.gamemodevpn

import android.content.Context
import android.net.*
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class NetworkMonitor(private val ctx: Context) {

    private val cm   = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())

    var onNetworkSwitch: ((String) -> Unit)? = null
    var onLinkPropertiesUpdate: ((String, Int, List<String>) -> Unit)? = null  // iface, mtu, dns

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = cm.getNetworkCapabilities(network)
            val type = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                else -> "Unknown"
            }
            Log.i(TAG, "Network available: $type")
            handler.post { onNetworkSwitch?.invoke(type) }
        }
        override fun onLost(network: Network) {
            Log.w(TAG, "⚠️ Network lost — reconnecting...")
            handler.post { onNetworkSwitch?.invoke("disconnected") }
        }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            val iface = lp.interfaceName ?: "?"
            val mtu   = lp.mtu
            val dns   = lp.dnsServers.map { it.hostAddress ?: "?" }
            Log.i(TAG, "🔗 LinkProps: iface=$iface mtu=$mtu dns=$dns")
            handler.post { onLinkPropertiesUpdate?.invoke(iface, mtu, dns) }
        }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val bw = caps.linkDownstreamBandwidthKbps / 1000
            Log.i(TAG, "📊 Bandwidth: ~${bw}Mbps")
        }
    }

    fun start() {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(req, callback)
        Log.i(TAG, "NetworkMonitor started")
    }

    fun stop() {
        try { cm.unregisterNetworkCallback(callback) } catch (e: Exception) { }
    }

    // Real per-UID traffic stats
    fun getUidTrafficMb(uid: Int): Pair<Float, Float> {
        val rx = android.net.TrafficStats.getUidRxBytes(uid).toFloat() / 1024 / 1024
        val tx = android.net.TrafficStats.getUidTxBytes(uid).toFloat() / 1024 / 1024
        return Pair(rx, tx)
    }

    // Total traffic since boot
    fun getTotalTrafficMb(): Pair<Float, Float> {
        val rx = android.net.TrafficStats.getTotalRxBytes().toFloat() / 1024 / 1024
        val tx = android.net.TrafficStats.getTotalTxBytes().toFloat() / 1024 / 1024
        return Pair(rx, tx)
    }

    // Get active DNS servers from system
    fun getActiveDnsServers(): List<String> {
        val active = cm.activeNetwork ?: return emptyList()
        val lp = cm.getLinkProperties(active) ?: return emptyList()
        return lp.dnsServers.map { it.hostAddress ?: "?" }
    }

    // Get active MTU
    fun getActiveMtu(): Int {
        val active = cm.activeNetwork ?: return 1500
        val lp = cm.getLinkProperties(active) ?: return 1500
        return lp.mtu.takeIf { it > 0 } ?: 1500
    }

    // WiFi channel & signal
    @Suppress("DEPRECATION")
    fun getWifiInfo(): WifiInfo? = try { wifi.connectionInfo } catch (_: Exception) { null }

    companion object { const val TAG = "NetworkMonitor" }
}
