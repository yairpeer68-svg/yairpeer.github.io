package com.yourname.gamemodevpn

import android.content.Context
import android.graphics.*
import android.view.View
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt

/**
 * Advanced analytics:
 * - Best time to play (by hour of day)
 * - Ping vs Temperature correlation (Pearson r)
 * - 7x24 heatmap visualization
 * - CSV export with error handling
 */
class NetworkAnalytics(private val ctx: Context) {

    private val db = SessionDatabase(ctx)

    data class HourStats(val hour: Int, val avgPing: Double, val sessions: Int, val rating: String)

    fun getBestHours(): List<HourStats> {
        val sessions = db.getLast(100)
        if (sessions.isEmpty()) return emptyList()
        val byHour = sessions.groupBy {
            Calendar.getInstance().also { c -> c.timeInMillis = it.startTime }.get(Calendar.HOUR_OF_DAY)
        }
        return (0..23).mapNotNull { hour ->
            val s = byHour[hour] ?: return@mapNotNull null
            val avg = s.map { it.avgPing }.average()
            val rating = when {
                avg < 30  -> "🟢 מעולה"
                avg < 60  -> "🟡 טוב"
                avg < 100 -> "🟠 בינוני"
                else      -> "🔴 גרוע"
            }
            HourStats(hour, avg, s.size, rating)
        }.sortedBy { it.avgPing }
    }

    fun getOverallStats() = db.getOverallStats()

    // Pearson correlation coefficient between ping and temperature
    fun computeCorrelation(pings: List<Int>, temps: List<Float>): Float {
        if (pings.size != temps.size || pings.size < 2) return 0f
        val n = pings.size
        val xMean = pings.average()
        val yMean = temps.map { it.toDouble() }.average()
        var cov = 0.0; var sx = 0.0; var sy = 0.0
        for (i in 0 until n) {
            val dx = pings[i] - xMean; val dy = temps[i] - yMean
            cov += dx * dy; sx += dx * dx; sy += dy * dy
        }
        return if (sx > 0 && sy > 0) (cov / sqrt(sx * sy)).toFloat() else 0f
    }

    fun exportToCsv(): Result<java.io.File> = runCatching {
        val sessions = db.getLast(500)
        val file = java.io.File(ctx.cacheDir, "gameboost_sessions.csv")
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        file.bufferedWriter().use { w ->
            w.write("Date,Game,Duration(min),AvgPing(ms),MinPing,MaxPing,PacketLoss%,Jitter(ms)\n")
            sessions.forEach { s ->
                w.write(
                    "${sdf.format(Date(s.startTime))},${s.game.replace(",", ";")}," +
                    "${s.durationSec / 60},${s.avgPing},${s.minPing},${s.maxPing}," +
                    "${String.format("%.2f", s.packetLoss)},${s.avgJitter}\n"
                )
            }
        }
        file
    }
}

// ── HeatmapView ──────────────────────────────────────────────────────────────
class HeatmapView(ctx: Context) : View(ctx) {

    private var data = Array(7) { FloatArray(24) { -1f } }
    private val days = listOf("א", "ב", "ג", "ד", "ה", "ו", "ש")

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B8AAA"); textSize = 20f; textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 16f; textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    fun setData(sessions: List<SessionRecord>) {
        val newData = Array(7) { FloatArray(24) { -1f } }
        val counts = Array(7) { IntArray(24) }
        sessions.forEach { s ->
            val cal = Calendar.getInstance().also { it.timeInMillis = s.startTime }
            val day = (cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)
            val hour = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            newData[day][hour] = if (newData[day][hour] < 0) s.avgPing.toFloat()
                                  else newData[day][hour] + s.avgPing
            counts[day][hour]++
        }
        for (d in 0..6) for (h in 0..23)
            if (counts[d][h] > 0) newData[d][h] /= counts[d][h].toFloat()
        data = newData
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val padL = 36f; val padT = 30f
        val cw = (width - padL) / 24f; val ch = (height - padT) / 7f

        for (h in 0..23 step 4)
            canvas.drawText("$h", padL + h * cw + cw / 2, padT - 6f, textPaint)

        for (d in 0..6) {
            canvas.drawText(days[d], padL / 2, padT + d * ch + ch / 2 + 8f, textPaint)
            for (h in 0..23) {
                val ping = data[d][h]
                cellPaint.color = when {
                    ping < 0   -> Color.parseColor("#172035")
                    ping < 30  -> Color.parseColor("#00FFAA")
                    ping < 60  -> Color.parseColor("#AAFF00")
                    ping < 100 -> Color.parseColor("#FF9500")
                    else       -> Color.parseColor("#FF3B6B")
                }
                val l = padL + h * cw + 1f; val t = padT + d * ch + 1f
                canvas.drawRoundRect(l, t, l + cw - 2f, t + ch - 2f, 4f, 4f, cellPaint)
                if (ping >= 0 && cw > 28f)
                    canvas.drawText("${ping.toInt()}", l + cw / 2f, t + ch / 2f + 5f, valuePaint)
            }
        }
    }
}
