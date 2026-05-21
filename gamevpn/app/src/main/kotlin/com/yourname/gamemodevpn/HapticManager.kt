package com.yourname.gamemodevpn

import android.content.Context
import android.os.*

/** Gaming haptic feedback patterns */
object HapticManager {

    fun activate(ctx: Context) = vibrate(ctx, longArrayOf(0, 50, 50, 100))
    fun deactivate(ctx: Context) = vibrate(ctx, longArrayOf(0, 100, 50, 50))
    fun spike(ctx: Context) = vibrate(ctx, longArrayOf(0, 30, 20, 30, 20, 30))
    fun success(ctx: Context) = vibrate(ctx, longArrayOf(0, 80))
    fun warning(ctx: Context) = vibrate(ctx, longArrayOf(0, 200, 100, 200))
    fun tick(ctx: Context) = vibrate(ctx, longArrayOf(0, 15))

    private fun vibrate(ctx: Context, pattern: LongArray) {
        try {
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else @Suppress("DEPRECATION") vib.vibrate(pattern, -1)
        } catch (e: Exception) { }
    }
}
