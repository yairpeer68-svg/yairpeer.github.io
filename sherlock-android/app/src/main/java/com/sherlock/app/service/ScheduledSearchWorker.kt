package com.sherlock.app.service

import android.content.Context
import androidx.work.*
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.SearchHistory
import com.sherlock.app.data.repository.UsernameSearchRepository
import com.sherlock.app.util.NotificationHelper
import kotlinx.coroutines.flow.toList
import java.util.concurrent.TimeUnit

class ScheduledSearchWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val notificationHelper = NotificationHelper(applicationContext)
        val repository = UsernameSearchRepository()
        val now = System.currentTimeMillis()

        val due = db.scheduledSearchDao().getDue(now)

        for (scheduled in due) {
            try {
                val results = repository.search(scheduled.query, scheduled.searchType).toList()
                val found = results.count { it.exists }

                val historyId = db.searchHistoryDao().insertHistory(
                    SearchHistory(
                        query = scheduled.query,
                        searchType = scheduled.searchType,
                        totalFound = found,
                        totalChecked = results.size
                    )
                )
                db.searchHistoryDao().insertResults(results.map { it.copy(historyId = historyId) })

                notificationHelper.showSearchCompleteNotification(scheduled.query, found, results.size)

                db.scheduledSearchDao().update(scheduled.copy(lastRun = now, lastFound = found))
            } catch (_: Exception) {
                db.scheduledSearchDao().update(scheduled.copy(lastRun = now))
            }
        }

        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ScheduledSearchWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sherlock_scheduled_search",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
