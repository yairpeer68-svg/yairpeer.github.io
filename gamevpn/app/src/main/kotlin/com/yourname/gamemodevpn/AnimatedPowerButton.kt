package com.yourname.gamemodevpn

import android.animation.*
import android.content.Context
import android.graphics.*
import android.view.*

class AnimatedPowerButton(ctx: Context) : View(ctx) {

    private var active = false
    private var pulseRadius = 0f
    private var pulseAlpha = 0f
    private var rotation = 0f
    private var glowIntensity = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val iconPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val bgPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val INACTIVE_COLOR = Color.parseColor("#3D5570")
    private val ACTIVE_COLOR   = Color.parseColor("#00C8FF")
    private val GLOW_COLOR     = Color.parseColor("#00C8FF")

    // Animators
    private var pulseAnim: ValueAnimator? = null
    private var rotateAnim: ValueAnimator? = null
    private var glowAnim: ValueAnimator? = null

    init { isClickable = true }

    fun setActive(a: Boolean) {
        active = a
        stopAnimations()
        if (a) startActiveAnimations() else { glowIntensity = 0f; pulseRadius = 0f; invalidate() }
    }

    private fun startActiveAnimations() {
        // Pulse ring
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { v ->
                val t = v.animatedValue as Float
                pulseRadius = t * width / 2f
                pulseAlpha = 1f - t
                invalidate()
            }
            start()
        }
        // Glow breathe
        glowAnim = ValueAnimator.ofFloat(0.3f, 1f, 0.3f).apply {
            duration = 2000; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { v -> glowIntensity = v.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnimations() {
        pulseAnim?.cancel(); glowAnim?.cancel(); rotateAnim?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(cx, cy) - 8f

        // Background circle
        bgPaint.color = if (active) Color.parseColor("#0A1F3A") else Color.parseColor("#0C1422")
        canvas.drawCircle(cx, cy, r, bgPaint)

        // Glow ring
        val col = if (active) ACTIVE_COLOR else INACTIVE_COLOR
        if (active && glowIntensity > 0) {
            ringPaint.color = col; ringPaint.strokeWidth = 4f + glowIntensity * 4f
            ringPaint.maskFilter = BlurMaskFilter(glowIntensity * 20f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(cx, cy, r, ringPaint)
            ringPaint.maskFilter = null
        }

        // Solid ring
        ringPaint.color = col; ringPaint.strokeWidth = 4f
        canvas.drawCircle(cx, cy, r, ringPaint)

        // Pulse ring
        if (active && pulseRadius > 0) {
            pulsePaint.color = col; pulsePaint.alpha = (pulseAlpha * 180).toInt()
            canvas.drawCircle(cx, cy, pulseRadius, pulsePaint)
        }

        // Power icon ⏻
        iconPaint.color = col; iconPaint.textSize = r * 1.1f
        canvas.drawText("⏻", cx, cy + r * 0.38f, iconPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) { performClick(); return true }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { stopAnimations(); super.onDetachedFromWindow() }
}
