package com.yourname.gamemodevpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Generates a weekly performance summary and posts it as a notification.
 * Scheduled via WorkManager (battery-friendly, survives reboots).
 */
object WeeklyReportManager {

    private const val TAG = "WeeklyReport"
    private const val WORK_NAME = "weekly_ping_report"
    const val CHANNEL_ID = "weekly_report"
    const val NOTIF_ID = 7003

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            req
        )
        Log.i(TAG, "Weekly report scheduled")
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
    }
}

class WeeklyReportWorker(
    private val ctx: Context,
    params: WorkerParameters
) : Worker(ctx, params) {

    override fun doWork(): Result {
        return try {
            val db = SessionDatabase(ctx)
            val sessions = db.getLast(200)
            if (sessions.isEmpty()) return Result.success()

            val weekAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
            val week = sessions.filter { it.startTime >= weekAgo }
            if (week.isEmpty()) return Result.success()

            val avgPing    = week.map { it.avgPing }.average().toInt()
            val bestPing   = week.minOf { it.minPing }
            val worstPing  = week.maxOf { it.maxPing }
            val avgLoss    = week.map { it.packetLoss.toDouble() }.average()
            val totalGames = week.size
            val totalMins  = week.sumOf { it.durationSec } / 60

            val trend = if (week.size >= 2) {
                val firstHalf = week.take(week.size / 2).map { it.avgPing }.average()
                val secondHalf = week.drop(week.size / 2).map { it.avgPing }.average()
                when {
                    secondHalf < firstHalf * 0.9  -> "📈 Ping ירד — נהדר!"
                    secondHalf > firstHalf * 1.1  -> "📉 Ping עלה — בדוק את החיבור"
                    else                           -> "➡️ Ping יציב"
                }
            } else "אין מספיק נתונים"

            val title = "דוח שבועי — $totalGames משחקים"
            val body  = "Avg: ${avgPing}ms | Best: ${bestPing}ms | Loss: ${"%.1f".format(avgLoss)}% | זמן: ${totalMins}min\n$trend"

            postNotification(title, body)
            Log.i("WeeklyReport", "Report: $body")
            Result.success()
        } catch (e: Exception) {
            Log.e("WeeklyReport", e.message ?: "")
            Result.retry()
        }
    }

    private fun postNotification(title: String, body: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                WeeklyReportManager.CHANNEL_ID, "דוח שבועי", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "סיכום ביצועי שבועי" }
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(ctx, WeeklyReportManager.CHANNEL_ID)
                .setContentTitle(title).setContentText(body).setStyle(android.app.Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentIntent(pi).setAutoCancel(true).build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(ctx)
                .setContentTitle(title).setContentText(body)
                .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentIntent(pi).setAutoCancel(true).build()
        }
        nm.notify(WeeklyReportManager.NOTIF_ID, notif)
    }
}
