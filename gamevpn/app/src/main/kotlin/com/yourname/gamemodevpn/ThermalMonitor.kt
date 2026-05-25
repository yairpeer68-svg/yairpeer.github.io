package com.yourname.gamemodevpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.*
import android.util.Log
import android.view.*
import android.widget.TextView
import android.app.NotificationManager

/**
 * Monitors battery/CPU temperature and shows a floating color-coded overlay.
 * Green (<38°C) → Yellow (<43°C) → Red (≥43°C + warning)
 */
class ThermalMonitor(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatView: TextView? = null
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastTemp = 0f

    companion object {
        const val TAG = "ThermalMonitor"
        const val TEMP_WARN  = 43f   // °C - show red warning
        const val TEMP_CAUTION = 38f // °C - show yellow
    }

    // ── Temperature reading ───────────────────────────────────────────────────
    private var latestTemp = 0f

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (rawTemp > 0) {
                latestTemp = rawTemp / 10f
                updateOverlay(latestTemp)
            }
        }
    }

    // ── Floating overlay ──────────────────────────────────────────────────────
    fun start() {
        if (running) return
        running = true
        createOverlay()
        ctx.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        Log.i(TAG, "🌡 Thermal monitor started")
    }

    fun stop() {
        if (!running) return
        running = false
        try { ctx.unregisterReceiver(batteryReceiver) } catch (e: Exception) { }
        handler.post {
            floatView?.let { wm.removeView(it); floatView = null }
        }
        Log.i(TAG, "🌡 Thermal monitor stopped")
    }

    private fun createOverlay() {
        handler.post {
            val tv = TextView(ctx).apply {
                text = "🌡 --°C"
                textSize = 12f
                setTextColor(Color.WHITE)
                typeface = Typeface.MONOSPACE
                setPadding(16, 8, 16, 8)
                setBackgroundColor(Color.argb(180, 10, 20, 40))
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 8; y = 120
            }

            // Drag support
            tv.setOnTouchListener(object : View.OnTouchListener {
                var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { initX = params.x; initY = params.y; touchX = e.rawX; touchY = e.rawY }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initX + (touchX - e.rawX).toInt()
                            params.y = initY + (e.rawY - touchY).toInt()
                            wm.updateViewLayout(tv, params)
                        }
                    }
                    return true
                }
            })

            try { wm.addView(tv, params); floatView = tv } catch (e: Exception) {
                Log.e(TAG, "Overlay error (need SYSTEM_ALERT_WINDOW): ${e.message}")
            }
        }
    }

    private fun updateOverlay(temp: Float) {
        handler.post {
            val tv = floatView ?: return@post
            val (color, emoji, warning) = when {
                temp >= TEMP_WARN    -> Triple(Color.rgb(255, 60, 80),  "🔴", " ⚠️")
                temp >= TEMP_CAUTION -> Triple(Color.rgb(255, 165, 0),  "🟡", "")
                else                 -> Triple(Color.rgb(0, 220, 120),  "🟢", "")
            }
            tv.text = "$emoji ${temp.toInt()}°C$warning"
            tv.setTextColor(color)

            // Red: vibrate + advisory toast
            if (temp >= TEMP_WARN && lastTemp < TEMP_WARN) {
                val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
                } else {
                    @Suppress("DEPRECATION") vib.vibrate(longArrayOf(0, 200, 100, 200), -1)
                }
                Log.w(TAG, "🌡 HIGH TEMP: ${temp}°C — throttling likely!")
                showBreakSuggestion()
            }
            lastTemp = temp
        }
    }

    private fun showBreakSuggestion() {
        // Pulse the overlay briefly to draw attention
        handler.post {
            floatView?.animate()
                ?.scaleX(1.3f)?.scaleY(1.3f)?.setDuration(200)
                ?.withEndAction {
                    floatView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(200)?.start()
                }?.start()
        }
    }

    fun getTemperature() = latestTemp
}
