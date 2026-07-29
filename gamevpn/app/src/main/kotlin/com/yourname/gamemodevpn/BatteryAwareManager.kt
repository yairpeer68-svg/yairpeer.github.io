package com.yourname.gamemodevpn

import android.content.*
import android.os.*
import android.util.Log

/**
 * Battery-aware game optimization:
 * - Low battery (<20%): reduce brightness, disable EQ, save mode
 * - Charging: activate sustained performance automatically
 * - Screen-off: keep VPN + CPU boost via WakeLock
 */
class BatteryAwareManager(private val ctx: Context) {

    private var isCharging = false
    private var batteryPct = 100
    private var screenOff = false

    var onLowBattery: (() -> Unit)? = null
    var onChargingStarted: (() -> Unit)? = null
    var onChargingStopped: (() -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val nowCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                      status == BatteryManager.BATTERY_STATUS_FULL
                    batteryPct = (level * 100 / scale)

                    if (batteryPct < 20) onLowBattery?.invoke()
                    if (nowCharging && !isCharging) onChargingStarted?.invoke()
                    if (!nowCharging && isCharging) onChargingStopped?.invoke()
                    isCharging = nowCharging
                    Log.d(TAG, "Battery: $batteryPct% | charging=$isCharging")
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true; Log.i(TAG, "Screen OFF — VPN continues via WakeLock")
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false; Log.i(TAG, "Screen ON")
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ctx.registerReceiver(receiver, filter)
        Log.i(TAG, "BatteryAwareManager started")
    }

    fun stop() {
        try { ctx.unregisterReceiver(receiver) } catch (e: Exception) { }
    }

    fun getBatteryPercent() = batteryPct
    fun isCharging() = isCharging
    fun isScreenOff() = screenOff
    fun isLowBattery() = batteryPct < 20

    companion object { const val TAG = "BatteryAware" }
}
