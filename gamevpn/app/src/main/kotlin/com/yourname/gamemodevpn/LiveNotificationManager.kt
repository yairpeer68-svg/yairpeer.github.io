package com.yourname.gamemodevpn

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Persistent notification with live-updating Ping, Loss, CPU, Temp.
 * Visible from everywhere — lock screen, status bar, notification shade.
 */
class LiveNotificationManager(private val ctx: Context) {

    companion object {
        const val CHANNEL_ID = "live_stats"
        const val NOTIF_ID   = 9001
    }

    private val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun init() {
        val ch = NotificationChannel(CHANNEL_ID, "Live Game Stats",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Ping, Loss, CPU בזמן אמת"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    fun update(ping: Int, lossPercent: Float, cpuPercent: Int, tempC: Float, game: String) {
        val pingColor = when { ping > 80 -> "🔴"; ping > 50 -> "🟡"; else -> "🟢" }
        val tempStr = if (tempC > 0) "  🌡${tempC.toInt()}°" else ""

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("⚡ $game — Game Mode ON")
            .setContentText("$pingColor Ping: ${ping}ms  |  Loss: ${String.format("%.1f",lossPercent)}%  |  CPU: $cpuPercent%$tempStr")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$pingColor Ping: ${ping}ms\n📦 Loss: ${String.format("%.1f",lossPercent)}%\n🖥 CPU: $cpuPercent%$tempStr"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_delete, "כבה",
                PendingIntent.getService(ctx, 0,
                    Intent(ctx, GameModeVpnService::class.java).apply { action = GameModeVpnService.ACTION_STOP },
                    PendingIntent.FLAG_IMMUTABLE))
            .build()

        nm.notify(NOTIF_ID, notif)
    }

    fun cancel() = nm.cancel(NOTIF_ID)
}
