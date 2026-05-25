package com.yourname.gamemodevpn

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import android.util.Log
import android.app.Application

class DisplayBoostManager(private val ctx: Context) {

    companion object { const val TAG = "DisplayBoost" }

    // ── 120Hz / max refresh rate ──────────────────────────────────────────────
    fun setMaxRefreshRate(context: Context) {
        val activity = context as? Activity ?: return
        try {
            val window = activity.window
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30+: setPreferredDisplayModeId
                val display = activity.display ?: return
                val modes = display.supportedModes
                val bestMode = modes.maxByOrNull { it.refreshRate }
                if (bestMode != null) {
                    window.attributes = window.attributes.also {
                        it.preferredDisplayModeId = bestMode.modeId
                    }
                    Log.i(TAG, "✅ Refresh rate → ${bestMode.refreshRate}Hz (mode ${bestMode.modeId})")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val display = activity.windowManager.defaultDisplay
                val modes = display.supportedModes
                val best = modes.maxByOrNull { it.refreshRate }
                if (best != null) {
                    window.attributes = window.attributes.also {
                        it.preferredDisplayModeId = best.modeId
                    }
                    Log.i(TAG, "✅ Refresh rate → ${best.refreshRate}Hz")
                }
            }
            // Keep screen on during game
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) { Log.w(TAG, "Refresh rate: ${e.message}") }
    }

    fun clearMaxRefreshRate(context: Context) {
        val activity = context as? Activity ?: return
        try {
            activity.window.attributes = activity.window.attributes.also { it.preferredDisplayModeId = 0 }
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) { }
    }

    // ── Brightness ────────────────────────────────────────────────────────────
    private var originalBrightness = -1

    fun saveBrightness() {
        originalBrightness = try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) { 128 }
    }

    fun setBrightness(level: Int) { // 0-255
        if (!Settings.System.canWrite(ctx)) return
        try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(10, 255))
            Log.i(TAG, "Brightness → $level")
        } catch (e: Exception) { Log.w(TAG, "Brightness: ${e.message}") }
    }

    fun restoreBrightness() {
        if (originalBrightness > 0) setBrightness(originalBrightness)
    }

    fun getMaxRefreshRate(): Float {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay
                display.supportedModes.maxOfOrNull { it.refreshRate } ?: 60f
            } else 60f
        } catch (e: Exception) { 60f }
    }
}
