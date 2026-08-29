package com.ghosteye.intelligence

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Constraints
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class WatchtowerWorker(appContext: Context, params: androidx.work.WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val api = ApiClient(applicationContext, ServerConfig.BASE_URL)
            // Server-side evaluation is owner-scoped and failure-isolated. Trigger it
            // before reading alerts so background notifications reflect fresh evidence.
            try { api.evaluateAllWatchlistsV20(100) } catch (_: Exception) { /* keep cached alerts usable */ }
            val payload = api.intelligenceAlertsV14(100)
            val alerts = payload.optJSONArray("items") ?: payload.optJSONArray("alerts") ?: JSONArray()
            val prefs = applicationContext.getSharedPreferences("ghost_eye_watchtower", Context.MODE_PRIVATE)
            val seen = prefs.getStringSet("seen_alert_ids", emptySet()).orEmpty().toMutableSet()
            val newIds = mutableListOf<String>()
            for (i in 0 until alerts.length()) {
                val a = alerts.optJSONObject(i) ?: continue
                val id = a.optString("id").ifBlank { a.optString("fingerprint") }
                if (id.isBlank() || id in seen) continue
                newIds += id
                val priority = a.optInt("priority_score", a.optJSONObject("payload")?.optInt("priority_score", 0) ?: 0)
                val severity = a.optString("severity", "info")
                val details = a.optJSONObject("payload")
                val message = details?.optString("entity")?.takeIf { it.isNotBlank() }
                    ?.let { "$severity • priority $priority • $it" }
                    ?: "$severity • priority $priority • New evidence detected"
                showNotification(
                    title = a.optString("title", "Ghost Eye Watchtower"),
                    message = message,
                    urgent = priority >= 90 || severity == "critical"
                )
            }
            if (newIds.isNotEmpty()) {
                val merged = (newIds + seen).take(500).toSet()
                prefs.edit().putStringSet("seen_alert_ids", merged).apply()
            }
            Result.success()
        } catch (e: SessionExpiredException) {
            Result.success() // login will refresh the session; do not hammer the server in background
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun showNotification(title: String, message: String, urgent: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val importance = if (urgent) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Watchtower alerts", importance))
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ghosteye)
            .setContentTitle(title.take(120))
            .setContentText(message.take(240))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(1000)))
            .setAutoCancel(true)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(applicationContext).notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "ghost_eye_watchtower"
        private const val WORK_NAME = "ghost_eye_watchtower_poll"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<WatchtowerWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
