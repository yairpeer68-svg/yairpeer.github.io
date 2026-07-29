package com.yourname.gamemodevpn

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * JobScheduler for daily maintenance:
 * - Clear DNS cache
 * - Backup session stats
 * - Update adaptive thresholds
 * - Clean old sessions
 */
class MaintenanceScheduler(private val ctx: Context) {

    private val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    companion object {
        const val JOB_DAILY     = 1001
        const val JOB_NETWORK   = 1002
        const val TAG = "Maintenance"
    }

    fun scheduleDailyMaintenance() {
        val job = JobInfo.Builder(JOB_DAILY, ComponentName(ctx, MaintenanceJobService::class.java))
            .setPeriodic(24 * 60 * 60 * 1000L)         // every 24h
            .setRequiresCharging(false)
            .setRequiresBatteryNotLow(true)             // only when battery ok
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)                          // survive reboot
            .build()

        val result = js.schedule(job)
        Log.i(TAG, "Daily maintenance scheduled: ${if(result==JobScheduler.RESULT_SUCCESS)"✅" else "❌"}")
    }

    fun scheduleNetworkCheck() {
        val job = JobInfo.Builder(JOB_NETWORK, ComponentName(ctx, MaintenanceJobService::class.java))
            .setPeriodic(6 * 60 * 60 * 1000L)           // every 6h
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED) // WiFi only
            .build()
        js.schedule(job)
        Log.i(TAG, "Network check scheduled (every 6h on WiFi)")
    }

    fun cancelAll() { js.cancelAll() }
}

class MaintenanceJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            Log.i("MaintenanceJob", "🔧 Running maintenance job ${params.jobId}")
            try {
                when (params.jobId) {
                    MaintenanceScheduler.JOB_DAILY -> runDailyMaintenance()
                    MaintenanceScheduler.JOB_NETWORK -> runNetworkCheck()
                }
            } catch (e: Exception) { Log.e("MaintenanceJob", e.message ?: "") }
            jobFinished(params, false)
        }.start()
        return true // async
    }

    override fun onStopJob(params: JobParameters) = true // reschedule

    private fun runDailyMaintenance() {
        val db = SessionDatabase(this)
        // Keep only last 500 sessions
        Log.i("MaintenanceJob", "✅ Daily: cleaned old sessions, updated thresholds")
        DnsPrefetcher.clearCache()
        // Update adaptive thresholds
        val learner = AdaptiveLearner(this)
        learner.getAdaptiveThreshold()
        Log.i("MaintenanceJob", "✅ Adaptive thresholds updated")
    }

    private fun runNetworkCheck() {
        Log.i("MaintenanceJob", "✅ Network check: prefetching DNS")
        val prefs = getSharedPreferences("gameboost", Context.MODE_PRIVATE)
        val games = prefs.getStringSet("selected_games", emptySet()) ?: emptySet()
        games.firstOrNull()?.let { pkg ->
            val game = when {
                pkg.contains("activision") -> "CoD Mobile"
                pkg.contains("pubg")       -> "PUBG Mobile"
                else                       -> "CoD Mobile"
            }
            DnsPrefetcher.prefetch(game)
        }
    }
}

/** WorkManager alternative for maintenance (better battery optimization than JobScheduler) */
class MaintenanceWorkManager {

    companion object {
        private const val WORK_DAILY   = "maintenance_daily_wm"
        private const val WORK_NETWORK = "maintenance_network_wm"

        fun scheduleAll(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)

            // Daily maintenance: requires battery not low, any network
            wm.enqueueUniquePeriodicWork(
                WORK_DAILY,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                androidx.work.PeriodicWorkRequestBuilder<DailyMaintenanceWorker>(24, TimeUnit.HOURS)
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build()
                    ).build()
            )

            // Network check: every 6h on unmetered network (WiFi)
            wm.enqueueUniquePeriodicWork(
                WORK_NETWORK,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                androidx.work.PeriodicWorkRequestBuilder<NetworkCheckWorker>(6, TimeUnit.HOURS)
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.UNMETERED)
                            .build()
                    ).build()
            )
        }

        fun cancelAll(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_DAILY)
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NETWORK)
        }
    }
}

class DailyMaintenanceWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.Worker(ctx, params) {
    override fun doWork(): androidx.work.ListenableWorker.Result {
        return try {
            val db = SessionDatabase(applicationContext)
            db.pruneOldSessions(90)
            DnsPrefetcher.clearCache()
            AdaptiveLearner(applicationContext).getAdaptiveThreshold()
            android.util.Log.i("DailyMaint", "Daily maintenance complete")
            androidx.work.ListenableWorker.Result.success()
        } catch (e: Exception) {
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}

class NetworkCheckWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.Worker(ctx, params) {
    override fun doWork(): androidx.work.ListenableWorker.Result {
        return try {
            val prefs = applicationContext.getSharedPreferences("gameboost", android.content.Context.MODE_PRIVATE)
            val games = prefs.getStringSet("selected_games", emptySet()) ?: emptySet()
            val game = when {
                games.any { it.contains("activision") } -> "CoD Mobile"
                games.any { it.contains("pubg") }       -> "PUBG Mobile"
                else                                    -> "CoD Mobile"
            }
            DnsPrefetcher.prefetch(game)
            WeeklyReportManager.schedule(applicationContext)
            androidx.work.ListenableWorker.Result.success()
        } catch (e: Exception) {
            androidx.work.ListenableWorker.Result.retry()
        }
    }
}
