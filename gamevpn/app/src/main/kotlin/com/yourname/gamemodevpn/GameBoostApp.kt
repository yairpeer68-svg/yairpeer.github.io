package com.yourname.gamemodevpn

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkManager

/**
 * Application class — global initialization on app start.
 * Must be registered in AndroidManifest.xml as android:name=".GameBoostApp"
 */
class GameBoostApp : Application(), Configuration.Provider {

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GameBoost starting...")

        // Migrate plain SharedPreferences → EncryptedSharedPreferences
        try { SecurePrefs.migrate(this) } catch (e: Exception) {
            Log.w(TAG, "Prefs migration: ${e.message}")
        }

        // Initialize WorkManager (required before any Worker scheduling)
        WorkManager.initialize(this, workManagerConfiguration)

        // Schedule weekly report (no-op if already scheduled)
        try { WeeklyReportManager.schedule(this) } catch (e: Exception) {
            Log.w(TAG, "WeeklyReport schedule: ${e.message}")
        }

        // Schedule WorkManager maintenance workers
        try { MaintenanceWorkManager.scheduleAll(this) } catch (e: Exception) {
            Log.w(TAG, "Maintenance schedule: ${e.message}")
        }

        // Register shortcuts
        val games = SecurePrefs.getStringSet(this, "selected_games")
        if (games.isNotEmpty()) {
            try { ShortcutHelper.register(this, games) } catch (e: Exception) {
                Log.w(TAG, "Shortcuts: ${e.message}")
            }
        }

        Log.i(TAG, "GameBoost initialized")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    companion object {
        private const val TAG = "GameBoostApp"
    }
}
