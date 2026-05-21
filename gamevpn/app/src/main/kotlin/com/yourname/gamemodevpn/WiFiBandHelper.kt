package com.yourname.gamemodevpn

import android.content.Context
import android.net.wifi.*
import android.util.Log

object WiFiBandHelper {

    fun get5GhzNetworks(ctx: Context): List<ScanResult> {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
            wm.scanResults.filter { it.frequency > 4900 } // 5GHz = 4900-5900MHz
                .sortedByDescending { it.level }
        } catch (e: Exception) { emptyList() }
    }

    fun getCurrent5GhzStatus(ctx: Context): String {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo ?: return "לא מחובר"
        val freq = info.frequency
        return when {
            freq > 4900 -> "✅ מחובר ל-5GHz (${freq}MHz)"
            freq > 2400 -> "⚠️ מחובר ל-2.4GHz — מומלץ לעבור ל-5GHz"
            else -> "❓ תדר לא ידוע"
        }
    }

    fun getRssiQuality(rssi: Int): String = when {
        rssi > -50 -> "🟢 מצוין ($rssi dBm)"
        rssi > -65 -> "🟡 טוב ($rssi dBm)"
        rssi > -80 -> "🟠 חלש ($rssi dBm)"
        else -> "🔴 גרוע ($rssi dBm)"
    }
}
