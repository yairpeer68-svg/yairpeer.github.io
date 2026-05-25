package com.yourname.gamemodevpn

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.util.Log

object SessionShareManager {

    fun shareSession(ctx: Context, session: SessionRecord) {
        val bmp = buildShareCard(session)
        val text = buildShareText(session)

        // Save bitmap to cache
        val file = java.io.File(ctx.cacheDir, "session_share.png")
        try {
            file.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, it) }
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, "שתף סטטיסטיקות").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            // Fallback: text only
            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
            ctx.startActivity(Intent.createChooser(intent, "שתף").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            Log.w("Share", "Image share failed: ${e.message}")
        }
    }

    private fun buildShareText(s: SessionRecord): String {
        val date = java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.startTime))
        return """
🎮 GameBoost Session Report
━━━━━━━━━━━━━━━━━━━━
🕐 $date | ${s.durationSec / 60} דקות
📡 Ping ממוצע: ${s.avgPing}ms
⚡ Ping הכי טוב: ${s.minPing}ms
📈 Ping הכי גרוע: ${s.maxPing}ms
📦 Packet Loss: ${String.format("%.1f", s.packetLoss)}%
〰️ Jitter: ${s.avgJitter}ms

Powered by PingBooster ⚡
        """.trimIndent()
    }

    private fun buildShareCard(s: SessionRecord): Bitmap {
        val w = 800; val h = 480
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // Background gradient
        val bg = Paint().also { it.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF070C18.toInt(), 0xFF0A1F3A.toInt(), Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        // Border
        val border = Paint().also { it.color = 0xFF00C8FF.toInt(); it.style = Paint.Style.STROKE; it.strokeWidth = 3f }
        canvas.drawRoundRect(8f, 8f, w - 8f, h - 8f, 24f, 24f, border)

        fun paint(color: Int, sz: Float, bold: Boolean = false, mono: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.color = color; it.textSize = sz
            it.typeface = if (mono) Typeface.MONOSPACE else if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        // Title
        canvas.drawText("⚡ GameBoost", 40f, 70f, paint(0xFF00C8FF.toInt(), 48f, bold = true))
        canvas.drawText("Session Report", 44f, 110f, paint(0xFF6B8AAA.toInt(), 28f))

        // Date
        val date = java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.startTime))
        canvas.drawText("$date  ·  ${s.durationSec / 60}min", w - 40f, 70f, paint(0xFF6B8AAA.toInt(), 24f).also { it.textAlign = Paint.Align.RIGHT })

        // Divider
        canvas.drawLine(40f, 140f, w - 40f, 140f, paint(0xFF172035.toInt(), 1f))

        // Stats
        val stats = listOf(
            Triple("Ping ממוצע",  "${s.avgPing}ms",                      0xFFFF9500.toInt()),
            Triple("הכי טוב",     "${s.minPing}ms",                      0xFF00FFAA.toInt()),
            Triple("הכי גרוע",   "${s.maxPing}ms",                      0xFFFF3B6B.toInt()),
            Triple("Packet Loss", "${String.format("%.1f", s.packetLoss)}%", 0xFFFF3B6B.toInt()),
            Triple("Jitter",      "${s.avgJitter}ms",                    0xFFA259FF.toInt())
        )
        stats.forEachIndexed { i, (label, value, color) ->
            val x = 40f + (i % 3) * 250f; val y = 220f + (i / 3) * 140f
            canvas.drawText(value, x, y, paint(color, 52f, bold = true, mono = true))
            canvas.drawText(label, x, y + 32f, paint(0xFF6B8AAA.toInt(), 22f))
        }

        // Footer
        canvas.drawText("Powered by PingBooster ⚡", w / 2f, h - 24f, paint(0xFF3D5570.toInt(), 20f).also { it.textAlign = Paint.Align.CENTER })

        return bmp
    }
}
