package com.sherlock.app.service

import android.content.Context
import androidx.work.*
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class AutoCleanWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val settings = SettingsManager(applicationContext)
        val days = settings.autoCleanDays.first()

        if (days > 0) {
            val db = AppDatabase.getInstance(applicationContext)
            val cutoff = System.currentTimeMillis() - days * 24L * 3600_000L
            db.searchHistoryDao().deleteHistoryBefore(cutoff)
        }

        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoCleanWorker>(1, TimeUnit.DAYS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sherlock_auto_clean",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
