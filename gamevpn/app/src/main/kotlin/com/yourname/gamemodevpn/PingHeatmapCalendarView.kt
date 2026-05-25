package com.yourname.gamemodevpn

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

/**
 * 7×24 heatmap — shows average ping for each hour of each weekday.
 * Green = fast, red = slow, grey = no data.
 * Tap a cell to see exact average.
 */
class PingHeatmapCalendarView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    // data[dayOfWeek 0-6][hour 0-23] = avg ping (0 = no data)
    private val data = Array(7) { IntArray(24) }

    private val days = listOf("א", "ב", "ג", "ד", "ה", "ו", "ש")
    private val hours = (0..23).map { if (it % 6 == 0) "$it" else "" }

    private val cellPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B8AAA"); textSize = 18f; textAlign = Paint.Align.CENTER
    }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#0A1F2E"); style = Paint.Style.FILL
    }

    fun loadFromDb(ctx: Context) {
        val sessions = SessionDatabase(ctx).getLast(500)
        val sums   = Array(7) { IntArray(24) }
        val counts = Array(7) { IntArray(24) }
        sessions.forEach { s ->
            val cal = Calendar.getInstance().also { it.timeInMillis = s.startTime }
            val dow = (cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)
            val hr  = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            sums[dow][hr]   += s.avgPing
            counts[dow][hr] += 1
        }
        for (d in 0..6) for (h in 0..23) {
            data[d][h] = if (counts[d][h] > 0) sums[d][h] / counts[d][h] else 0
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val labelW = 32f
        val labelH = 24f
        val cellW = (w - labelW) / 24f
        val cellH = (h - labelH) / 7f

        val allPings = data.flatMap { it.toList() }.filter { it > 0 }
        val minPing = allPings.minOrNull() ?: 20
        val maxPing = allPings.maxOrNull()?.coerceAtLeast(minPing + 1) ?: 200

        // Draw cells
        for (d in 0..6) {
            for (hr in 0..23) {
                val x = labelW + hr * cellW
                val y = labelH + d * cellH
                val ping = data[d][hr]
                cellPaint.color = if (ping == 0) Color.parseColor("#0D1F30")
                    else pingToColor(ping, minPing, maxPing)
                canvas.drawRect(x + 1, y + 1, x + cellW - 1, y + cellH - 1, cellPaint)
            }
        }

        // Day labels
        for (d in 0..6) {
            val y = labelH + d * cellH + cellH / 2f + 7f
            canvas.drawText(days[d], labelW / 2f, y, textPaint)
        }

        // Hour labels
        for (hr in 0..23) {
            val label = hours[hr]
            if (label.isNotEmpty()) {
                val x = labelW + hr * cellW + cellW / 2f
                canvas.drawText(label, x, labelH - 4f, textPaint)
            }
        }
    }

    private fun pingToColor(ping: Int, min: Int, max: Int): Int {
        val t = ((ping - min).toFloat() / (max - min)).coerceIn(0f, 1f)
        val r = (t * 255).toInt()
        val g = ((1f - t) * 180).toInt()
        return Color.rgb(r, g, 40)
    }
}
