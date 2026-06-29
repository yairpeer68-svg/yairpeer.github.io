package com.sherlock.app.service

import android.content.Context
import androidx.work.*
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.util.NotificationHelper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class MonitorWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val notificationHelper = NotificationHelper(applicationContext)
        val now = System.currentTimeMillis()

        val profiles = db.monitoredProfileDao().getProfilesDueForCheck(now - 3600_000)

        for (profile in profiles) {
            try {
                val request = Request.Builder()
                    .url(profile.url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""
                response.close()

                val currentHash = md5(body)
                val isOnline = response.code in 200..299

                if (profile.profileImageHash.isNotEmpty() && currentHash != profile.profileImageHash) {
                    notificationHelper.showProfileChangeNotification(
                        profile.siteName,
                        profile.username,
                        "תוכן הדף השתנה"
                    )
                }

                if (profile.lastStatus != isOnline) {
                    notificationHelper.showProfileChangeNotification(
                        profile.siteName,
                        profile.username,
                        if (isOnline) "הפרופיל חזר לאוויר" else "הפרופיל הוסר"
                    )
                }

                db.monitoredProfileDao().updateProfile(
                    profile.copy(
                        lastChecked = now,
                        lastStatus = isOnline,
                        profileImageHash = currentHash
                    )
                )
            } catch (_: Exception) {
                db.monitoredProfileDao().updateProfile(profile.copy(lastChecked = now))
            }
        }

        return Result.success()
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<MonitorWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sherlock_monitor",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
