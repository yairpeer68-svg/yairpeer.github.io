package com.yourname.gamemodevpn

import android.content.Context
import android.os.Build
import android.telephony.*
import android.util.Log

data class CellInfo(
    val technology: String,  // 5G / LTE / 3G
    val operator: String,
    val signalDbm: Int,
    val signalBars: Int,     // 0-4
    val band: String,
    val rating: String
)

class CellMonitor(private val ctx: Context) {

    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    companion object { const val TAG = "CellMonitor" }

    fun getCellInfo(): CellInfo {
        return try {
            val operator = tm.networkOperatorName.ifEmpty { "Unknown" }
            val tech = getNetworkTech()
            val (dbm, bars) = getSignalStrength()
            val band = getBand()
            val rating = when {
                dbm > -70 -> "🟢 מצוין"
                dbm > -85 -> "🟡 טוב"
                dbm > -100 -> "🟠 חלש"
                else -> "🔴 גרוע"
            }
            CellInfo(tech, operator, dbm, bars, band, rating)
        } catch (e: Exception) {
            CellInfo("N/A", "N/A", -999, 0, "N/A", "❓")
        }
    }

    private fun getNetworkTech(): String {
        return when (tm.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR     -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE    -> "4G LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSPA   -> "3G HSPA+"
            TelephonyManager.NETWORK_TYPE_UMTS   -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE   -> "2G EDGE"
            TelephonyManager.NETWORK_TYPE_GPRS   -> "2G GPRS"
            else -> "Unknown"
        }
    }

    private fun getSignalStrength(): Pair<Int, Int> {
        return try {
            val cells = tm.allCellInfo ?: return Pair(-100, 0)
            for (cell in cells) {
                if (!cell.isRegistered) continue
                return when (cell) {
                    is CellInfoLte -> {
                        val dbm = cell.cellSignalStrength.dbm
                        Pair(dbm, cell.cellSignalStrength.level)
                    }
                    is CellInfoNr -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val dbm = (cell.cellSignalStrength as CellSignalStrengthNr).dbm
                        Pair(dbm, cell.cellSignalStrength.level)
                    } else Pair(-100, 0)
                    is CellInfoWcdma -> {
                        val dbm = cell.cellSignalStrength.dbm
                        Pair(dbm, cell.cellSignalStrength.level)
                    }
                    else -> Pair(-100, 0)
                }
            }
            Pair(-100, 0)
        } catch (e: Exception) { Pair(-100, 0) }
    }

    private fun getBand(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "Band ${tm.dataNetworkType}"
            } else "N/A"
        } catch (e: Exception) { "N/A" }
    }

    fun isRoaming() = tm.isNetworkRoaming
    fun getMobileCountry() = tm.networkCountryIso.uppercase()
}
