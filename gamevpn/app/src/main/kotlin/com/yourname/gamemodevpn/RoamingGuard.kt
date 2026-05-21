package com.yourname.gamemodevpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Roaming Guard:
 * - Detects roaming immediately via broadcast
 * - Monitors mobile data usage while roaming
 * - Warns + optionally blocks game traffic to prevent huge bills
 */
class RoamingGuard(private val ctx: Context) {

    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var lastRxBytes = 0L
    private var roamingDataMb = 0f
    private var monitorThread: Thread? = null
    private var active = false

    var onRoamingDetected: ((String) -> Unit)? = null
    var onHighRoamingUsage: ((Float) -> Unit)? = null

    companion object {
        const val TAG = "RoamingGuard"
        const val WARN_MB = 50f  // warn after 50MB while roaming
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!tm.isNetworkRoaming) return
            val country = tm.networkCountryIso.uppercase()
            val operator = tm.networkOperatorName
            val msg = "⚠️ נדידה: $operator ($country) — עלויות גבוהות!"
            Log.w(TAG, msg)
            onRoamingDetected?.invoke(msg)
        }
    }

    fun start() {
        if (active) return; active = true
        ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_NETWORK_COUNTRY_CHANGED))
        lastRxBytes = TrafficStats.getMobileRxBytes()
        monitorRoamingUsage()
        Log.i(TAG, "✅ RoamingGuard active")
    }

    private fun monitorRoamingUsage() {
        monitorThread = Thread {
            while (active) {
                try {
                    Thread.sleep(10_000) // check every 10s
                    if (!tm.isNetworkRoaming) continue
                    val currentRx = TrafficStats.getMobileRxBytes()
                    val deltaMb = (currentRx - lastRxBytes).toFloat() / 1024 / 1024
                    roamingDataMb += deltaMb
                    lastRxBytes = currentRx
                    if (roamingDataMb > WARN_MB) {
                        Log.w(TAG, "🚨 Roaming usage: ${roamingDataMb.toInt()}MB!")
                        onHighRoamingUsage?.invoke(roamingDataMb)
                    }
                } catch (e: InterruptedException) { break }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        active = false; monitorThread?.interrupt()
        try { ctx.unregisterReceiver(receiver) } catch (e: Exception) { }
    }

    fun isRoaming() = tm.isNetworkRoaming
    fun getRoamingCountry() = tm.networkCountryIso.uppercase()
    fun getRoamingDataMb() = roamingDataMb
}
