package com.ghosteye.intelligence

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberNetworkAvailable(): Boolean {
    val context = LocalContext.current
    val cm = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    fun current(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    var available by remember { mutableStateOf(current()) }
    val main = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(cm) {
        fun update() { main.post { available = current() } }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { update() }
            override fun onLost(network: Network) { update() }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { update() }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }
    return available
}
