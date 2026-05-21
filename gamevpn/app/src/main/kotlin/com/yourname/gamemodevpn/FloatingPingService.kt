package com.yourname.gamemodevpn

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.util.Log
import android.view.*
import android.widget.TextView
import kotlinx.coroutines.*

/**
 * Floating ping overlay — shows live ping as a draggable bubble
 * visible during gameplay over all apps.
 * Requires: android.permission.SYSTEM_ALERT_WINDOW
 * Start via: startService(Intent(ctx, FloatingPingService::class.java))
 */
class FloatingPingService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var overlayView: TextView
    private var pingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        const val TAG = "FloatingPing"
        const val NOTIF_ID = 7002
        const val CHANNEL_ID = "floating_ping"
        @Volatile var isRunning = false

        fun start(ctx: Context) {
            if (isRunning) return
            ctx.startService(Intent(ctx, FloatingPingService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingPingService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        createOverlay()
        startPingUpdates()
        isRunning = true
        Log.i(TAG, "Floating ping overlay started")
    }

    private fun createOverlay() {
        overlayView = TextView(this).apply {
            text = "-- ms"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setPadding(12, 6, 12, 6)
            background = createRoundedBackground(Color.parseColor("#CC0A1F2E"), 20f)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16; y = 200
        }

        overlayView.setOnTouchListener(DragTouchListener(overlayView, params, wm))
        wm.addView(overlayView, params)
    }

    private fun startPingUpdates() {
        pingJob = scope.launch {
            while (isActive) {
                val ping = try {
                    HappyEyeballs.measureLatency("1.1.1.1", 53)
                } catch (_: Exception) { -1L }

                val (text, color) = when {
                    ping <= 0  -> "--ms" to Color.parseColor("#888888")
                    ping < 50  -> "${ping}ms" to Color.parseColor("#00FFAA")
                    ping < 100 -> "${ping}ms" to Color.parseColor("#FF9500")
                    else       -> "${ping}ms" to Color.parseColor("#FF3B6B")
                }
                overlayView.text = text
                overlayView.setTextColor(color)

                delay(1200)
            }
        }
    }

    private fun createRoundedBackground(color: Int, radius: Float): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
    }

    override fun onDestroy() {
        isRunning = false
        pingJob?.cancel()
        scope.cancel()
        try { wm.removeView(overlayView) } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Ping Overlay", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false); description = "Floating ping bubble"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getService(this, 0,
            Intent(this, FloatingPingService::class.java), PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Ping Overlay פעיל")
                .setContentText("גרור את הבועה להזזה")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Ping Overlay פעיל")
                .setContentText("גרור את הבועה להזזה")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true).build()
        }
    }

    /** Touch listener that makes the overlay draggable */
    private class DragTouchListener(
        private val view: View,
        private val params: WindowManager.LayoutParams,
        private val wm: WindowManager
    ) : View.OnTouchListener {
        private var startX = 0f; private var startY = 0f
        private var startParamX = 0; private var startParamY = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    startParamX = params.x; startParamY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startParamX + (e.rawX - startX)).toInt()
                    params.y = (startParamY + (e.rawY - startY)).toInt()
                    wm.updateViewLayout(view, params)
                }
            }
            return true
        }
    }
}
