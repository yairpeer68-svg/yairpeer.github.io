package com.yourname.gamemodevpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

class PingGraphView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val targetData = mutableListOf<Int>()
    private val displayData = mutableListOf<Float>() // animated values
    private var spikeThreshold = 80
    private var animator: ValueAnimator? = null

    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00C8FF"); strokeWidth = 3f; style = Paint.Style.STROKE
        pathEffect = CornerPathEffect(8f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val spikePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B6B"); strokeWidth = 2f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B8AAA"); textSize = 22f; typeface = Typeface.MONOSPACE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3D5570"); textSize = 20f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun update(pings: List<Int>, threshold: Int = 80) {
        val incoming = pings.takeLast(60)
        spikeThreshold = threshold
        if (incoming.isEmpty()) { targetData.clear(); displayData.clear(); invalidate(); return }

        // Sync display list length with target
        while (displayData.size < incoming.size) displayData.add(incoming.first().toFloat())
        while (displayData.size > incoming.size) displayData.removeAt(0)

        animator?.cancel()
        val startSnapshot = displayData.toFloatArray()
        val endSnapshot = incoming.map { it.toFloat() }.toFloatArray()

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedFraction
                for (i in displayData.indices) {
                    displayData[i] = startSnapshot[i] + (endSnapshot[i] - startSnapshot[i]) * t
                }
                invalidate()
            }
            start()
        }

        targetData.clear(); targetData.addAll(incoming)
    }

    override fun onDraw(canvas: Canvas) {
        if (displayData.isEmpty()) { drawEmpty(canvas); return }

        val w = width.toFloat(); val h = height.toFloat()
        val padL = 60f; val padR = 16f; val padT = 16f; val padB = 28f
        val graphW = w - padL - padR; val graphH = h - padT - padB

        val maxPing = maxOf(displayData.max(), (spikeThreshold + 20).toFloat())
        val minPing = maxOf(0f, displayData.min() - 10f)
        val range = maxOf(maxPing - minPing, 1f)

        fun px(i: Int) = padL + i.toFloat() / (displayData.size - 1).coerceAtLeast(1) * graphW
        fun py(v: Float) = padT + (1f - (v - minPing) / range) * graphH

        // Gradient fill
        val shader = LinearGradient(0f, padT, 0f, padT + graphH,
            Color.parseColor("#2200C8FF"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        fillPaint.shader = shader
        val fillPath = Path()
        fillPath.moveTo(px(0), padT + graphH)
        displayData.forEachIndexed { i, v -> fillPath.lineTo(px(i), py(v)) }
        fillPath.lineTo(px(displayData.size - 1), padT + graphH)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)

        // Line
        val linePath = Path()
        displayData.forEachIndexed { i, v ->
            if (i == 0) linePath.moveTo(px(i), py(v)) else linePath.lineTo(px(i), py(v))
        }
        canvas.drawPath(linePath, linePaint)

        // Spike threshold line
        val threshY = py(spikeThreshold.toFloat())
        canvas.drawLine(padL, threshY, w - padR, threshY, spikePaint)
        canvas.drawText("${spikeThreshold}ms", 2f, threshY - 4f, labelPaint)

        // Last animated value
        val last = displayData.last()
        val col = when {
            last >= spikeThreshold -> Color.parseColor("#FF3B6B")
            last >= spikeThreshold * 0.7f -> Color.parseColor("#FF9500")
            else -> Color.parseColor("#00FFAA")
        }
        textPaint.color = col
        canvas.drawText("${last.toInt()}ms", w - padR - 80f, padT + 24f, textPaint)

        // Y-axis labels
        listOf(minPing, (minPing + maxPing) / 2f, maxPing).forEach { v ->
            canvas.drawText("${v.toInt()}", 2f, py(v) + 8f, labelPaint)
        }

        // Animated dot at last point
        dotPaint.color = col
        canvas.drawCircle(px(displayData.size - 1), py(last), 6f, dotPaint)
    }

    private fun drawEmpty(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3D5570"); textSize = 28f; textAlign = Paint.Align.CENTER
        }
        canvas.drawText("אין נתונים עדיין", width / 2f, height / 2f, p)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
