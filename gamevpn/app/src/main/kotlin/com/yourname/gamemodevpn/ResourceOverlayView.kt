package com.yourname.gamemodevpn

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Floating HUD overlay showing live: CPU% · RAM · Ping · Temp · Cores
 * Draggable, minimal, color-coded
 */
class ResourceOverlayView(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var active = false

    // Stat views
    private lateinit var tvCpu: TextView
    private lateinit var tvRam: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvTemp: TextView
    private lateinit var tvCores: TextView

    fun show() {
        if (active) return
        active = true
        handler.post { createView() }
    }

    fun hide() {
        if (!active) return
        active = false
        handler.post {
            rootView?.let { try { wm.removeView(it) } catch (e: Exception) {} }
            rootView = null
        }
    }

    fun updateStats(cpu: Int, availRam: Int, totalRam: Int, ping: Int, tempC: Float, cores: Int) {
        if (!active) return
        handler.post {
            val cpuCol = when { cpu > 80 -> Color.parseColor("#FF3B6B"); cpu > 60 -> Color.parseColor("#FF9500"); else -> Color.parseColor("#00FFAA") }
            val ramUsed = totalRam - availRam
            val ramPct = if (totalRam > 0) ramUsed * 100 / totalRam else 0
            val ramCol = when { ramPct > 85 -> Color.parseColor("#FF3B6B"); ramPct > 70 -> Color.parseColor("#FF9500"); else -> Color.parseColor("#00C8FF") }
            val pingCol = when { ping > 80 -> Color.parseColor("#FF3B6B"); ping > 50 -> Color.parseColor("#FF9500"); else -> Color.parseColor("#00FFAA") }
            val tempCol = when { tempC > 43f -> Color.parseColor("#FF3B6B"); tempC > 38f -> Color.parseColor("#FF9500"); else -> Color.parseColor("#00FFAA") }

            if (::tvCpu.isInitialized) {
                tvCpu.text  = "CPU: ${cpu}%";  tvCpu.setTextColor(cpuCol)
                tvRam.text  = "RAM: ${ramPct}% (${availRam}MB free)"; tvRam.setTextColor(ramCol)
                tvPing.text = "Ping: ${ping}ms"; tvPing.setTextColor(pingCol)
                tvTemp.text = "Temp: ${tempC.toInt()}°C"; tvTemp.setTextColor(tempCol)
                tvCores.text = "Cores: $cores · SCHED_FIFO"
            }
        }
    }

    private fun createView() {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 7, 12, 24))
            setPadding(20, 14, 20, 14)
        }

        fun statTv(text: String, color: Int) = TextView(ctx).apply {
            this.text = text; textSize = 10f; setTextColor(color)
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 3 }
        }

        val header = TextView(ctx).apply {
            text = "⚡ GameBoost HUD"; textSize = 10f; setTextColor(Color.parseColor("#00C8FF"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 8 }
        }

        tvCpu   = statTv("CPU: --%",  Color.parseColor("#00FFAA"))
        tvRam   = statTv("RAM: --",   Color.parseColor("#00C8FF"))
        tvPing  = statTv("Ping: --ms",Color.parseColor("#FF9500"))
        tvTemp  = statTv("Temp: --°C",Color.parseColor("#00FFAA"))
        tvCores = statTv("Cores: --", Color.parseColor("#6B8AAA"))

        root.addView(header); root.addView(tvCpu); root.addView(tvRam)
        root.addView(tvPing); root.addView(tvTemp); root.addView(tvCores)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 8; y = 200 }

        // Drag support
        root.setOnTouchListener(object : View.OnTouchListener {
            var ix = 0; var iy = 0; var tx = 0f; var ty = 0f
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> { params.x = ix + (e.rawX - tx).toInt(); params.y = iy + (e.rawY - ty).toInt(); wm.updateViewLayout(root, params) }
                }
                return true
            }
        })

        try { wm.addView(root, params); rootView = root }
        catch (e: Exception) { android.util.Log.e("Overlay", "Need SYSTEM_ALERT_WINDOW: ${e.message}") }
    }
}
