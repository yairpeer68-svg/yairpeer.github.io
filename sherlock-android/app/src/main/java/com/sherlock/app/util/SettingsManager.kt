package com.sherlock.app.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.sherlock.app.data.model.AppLanguage
import com.sherlock.app.data.model.AppTheme
import com.sherlock.app.data.model.FakeAppIcon
import com.sherlock.app.data.model.FontScale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        val PARALLEL_THREADS = intPreferencesKey("parallel_threads")
        val SEARCH_TIMEOUT = intPreferencesKey("search_timeout")
        val SHOW_ADULT = booleanPreferencesKey("show_adult")
        val AUTO_VARIATIONS = booleanPreferencesKey("auto_variations")
        val FAKE_ICON = stringPreferencesKey("fake_icon")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val MONITOR_INTERVAL = intPreferencesKey("monitor_interval")
        val FIRST_LAUNCH = booleanPreferencesKey("first_launch")
        val TOTAL_SEARCHES = intPreferencesKey("total_searches_counter")
        val FONT_SCALE = stringPreferencesKey("font_scale")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val COMPACT_DENSITY = booleanPreferencesKey("compact_density")
        val PINNED_TILES = stringSetPreferencesKey("pinned_home_tiles")
        val AUTO_FAVORITE_ON_FOUND = booleanPreferencesKey("auto_favorite_on_found")
        val AUTO_EXPORT_ON_COMPLETE = booleanPreferencesKey("auto_export_on_complete")
        val AUTO_CLEAN_DAYS = intPreferencesKey("auto_clean_history_days")
        val WORKFLOW_COMPLETED_STEPS = stringSetPreferencesKey("workflow_completed_steps")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_PIN_HASH = stringPreferencesKey("app_lock_pin_hash")
        val APP_LOCK_PIN_SALT = stringPreferencesKey("app_lock_pin_salt")
        val AUTO_LOCK_TIMEOUT_MINUTES = intPreferencesKey("auto_lock_timeout_minutes")
        val SCREENSHOT_PROTECTION = booleanPreferencesKey("screenshot_protection")
        val GLOBAL_INCOGNITO = booleanPreferencesKey("global_incognito")
        val LAST_BACKGROUND_TIME = longPreferencesKey("last_background_time")
        val HIBP_API_KEY = stringPreferencesKey("hibp_api_key")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val CLIPBOARD_MONITOR = booleanPreferencesKey("clipboard_monitor")
    }

    val theme: Flow<AppTheme> = context.dataStore.data.map {
        try { AppTheme.valueOf(it[THEME] ?: AppTheme.DARK_BLUE.name) } catch (_: Exception) { AppTheme.DARK_BLUE }
    }

    val language: Flow<AppLanguage> = context.dataStore.data.map {
        try { AppLanguage.valueOf(it[LANGUAGE] ?: AppLanguage.HEBREW.name) } catch (_: Exception) { AppLanguage.HEBREW }
    }

    val hapticFeedback: Flow<Boolean> = context.dataStore.data.map { it[HAPTIC_FEEDBACK] ?: true }
    val parallelThreads: Flow<Int> = context.dataStore.data.map { it[PARALLEL_THREADS] ?: 10 }
    val searchTimeout: Flow<Int> = context.dataStore.data.map { it[SEARCH_TIMEOUT] ?: 10 }
    val showAdult: Flow<Boolean> = context.dataStore.data.map { it[SHOW_ADULT] ?: false }
    val autoVariations: Flow<Boolean> = context.dataStore.data.map { it[AUTO_VARIATIONS] ?: true }
    val notifications: Flow<Boolean> = context.dataStore.data.map { it[NOTIFICATIONS] ?: true }
    val monitorInterval: Flow<Int> = context.dataStore.data.map { it[MONITOR_INTERVAL] ?: 60 }
    val isFirstLaunch: Flow<Boolean> = context.dataStore.data.map { it[FIRST_LAUNCH] ?: true }
    val fontScale: Flow<FontScale> = context.dataStore.data.map {
        try { FontScale.valueOf(it[FONT_SCALE] ?: FontScale.MEDIUM.name) } catch (_: Exception) { FontScale.MEDIUM }
    }
    val reduceMotion: Flow<Boolean> = context.dataStore.data.map { it[REDUCE_MOTION] ?: false }
    val compactDensity: Flow<Boolean> = context.dataStore.data.map { it[COMPACT_DENSITY] ?: false }
    val pinnedTiles: Flow<Set<String>> = context.dataStore.data.map { it[PINNED_TILES] ?: emptySet() }
    val autoFavoriteOnFound: Flow<Boolean> = context.dataStore.data.map { it[AUTO_FAVORITE_ON_FOUND] ?: false }
    val autoExportOnComplete: Flow<Boolean> = context.dataStore.data.map { it[AUTO_EXPORT_ON_COMPLETE] ?: false }
    val autoCleanDays: Flow<Int> = context.dataStore.data.map { it[AUTO_CLEAN_DAYS] ?: 0 }
    val workflowCompletedSteps: Flow<Set<String>> = context.dataStore.data.map { it[WORKFLOW_COMPLETED_STEPS] ?: emptySet() }
    val demoMode: Flow<Boolean> = context.dataStore.data.map { it[DEMO_MODE] ?: false }
    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[APP_LOCK_ENABLED] ?: false }
    val appLockPinHash: Flow<String?> = context.dataStore.data.map { it[APP_LOCK_PIN_HASH] }
    val autoLockTimeoutMinutes: Flow<Int> = context.dataStore.data.map { it[AUTO_LOCK_TIMEOUT_MINUTES] ?: 0 }
    val screenshotProtection: Flow<Boolean> = context.dataStore.data.map { it[SCREENSHOT_PROTECTION] ?: false }
    val globalIncognito: Flow<Boolean> = context.dataStore.data.map { it[GLOBAL_INCOGNITO] ?: false }
    val hibpApiKey: Flow<String> = context.dataStore.data.map { it[HIBP_API_KEY] ?: "" }

    suspend fun setTheme(theme: AppTheme) { context.dataStore.edit { it[THEME] = theme.name } }
    suspend fun setLanguage(lang: AppLanguage) { context.dataStore.edit { it[LANGUAGE] = lang.name } }
    suspend fun setHapticFeedback(enabled: Boolean) { context.dataStore.edit { it[HAPTIC_FEEDBACK] = enabled } }
    suspend fun setParallelThreads(threads: Int) { context.dataStore.edit { it[PARALLEL_THREADS] = threads } }
    suspend fun setSearchTimeout(seconds: Int) { context.dataStore.edit { it[SEARCH_TIMEOUT] = seconds } }
    suspend fun setShowAdult(show: Boolean) { context.dataStore.edit { it[SHOW_ADULT] = show } }
    suspend fun setAutoVariations(enabled: Boolean) { context.dataStore.edit { it[AUTO_VARIATIONS] = enabled } }
    suspend fun setNotifications(enabled: Boolean) { context.dataStore.edit { it[NOTIFICATIONS] = enabled } }
    suspend fun setMonitorInterval(minutes: Int) { context.dataStore.edit { it[MONITOR_INTERVAL] = minutes } }
    suspend fun setFirstLaunch(first: Boolean) { context.dataStore.edit { it[FIRST_LAUNCH] = first } }
    suspend fun incrementSearchCount() { context.dataStore.edit { it[TOTAL_SEARCHES] = (it[TOTAL_SEARCHES] ?: 0) + 1 } }
    suspend fun setFontScale(scale: FontScale) { context.dataStore.edit { it[FONT_SCALE] = scale.name } }
    suspend fun setReduceMotion(enabled: Boolean) { context.dataStore.edit { it[REDUCE_MOTION] = enabled } }
    suspend fun setCompactDensity(enabled: Boolean) { context.dataStore.edit { it[COMPACT_DENSITY] = enabled } }
    suspend fun togglePinnedTile(key: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[PINNED_TILES] ?: emptySet()
            prefs[PINNED_TILES] = if (key in current) current - key else current + key
        }
    }
    suspend fun setAutoFavoriteOnFound(enabled: Boolean) { context.dataStore.edit { it[AUTO_FAVORITE_ON_FOUND] = enabled } }
    suspend fun setAutoExportOnComplete(enabled: Boolean) { context.dataStore.edit { it[AUTO_EXPORT_ON_COMPLETE] = enabled } }
    suspend fun setAutoCleanDays(days: Int) { context.dataStore.edit { it[AUTO_CLEAN_DAYS] = days } }
    suspend fun toggleWorkflowStep(stepId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[WORKFLOW_COMPLETED_STEPS] ?: emptySet()
            prefs[WORKFLOW_COMPLETED_STEPS] = if (stepId in current) current - stepId else current + stepId
        }
    }
    suspend fun resetWorkflowSteps() { context.dataStore.edit { it[WORKFLOW_COMPLETED_STEPS] = emptySet() } }
    suspend fun setDemoMode(enabled: Boolean) { context.dataStore.edit { it[DEMO_MODE] = enabled } }

    suspend fun setAppLockEnabled(enabled: Boolean) { context.dataStore.edit { it[APP_LOCK_ENABLED] = enabled } }
    suspend fun setAutoLockTimeoutMinutes(minutes: Int) { context.dataStore.edit { it[AUTO_LOCK_TIMEOUT_MINUTES] = minutes } }
    suspend fun setScreenshotProtection(enabled: Boolean) { context.dataStore.edit { it[SCREENSHOT_PROTECTION] = enabled } }
    suspend fun setGlobalIncognito(enabled: Boolean) { context.dataStore.edit { it[GLOBAL_INCOGNITO] = enabled } }
    suspend fun setHibpApiKey(key: String) { context.dataStore.edit { it[HIBP_API_KEY] = key } }
    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { it[KEEP_SCREEN_ON] ?: false }
    suspend fun setKeepScreenOn(enabled: Boolean) { context.dataStore.edit { it[KEEP_SCREEN_ON] = enabled } }
    val clipboardMonitor: Flow<Boolean> = context.dataStore.data.map { it[CLIPBOARD_MONITOR] ?: false }
    suspend fun setClipboardMonitor(enabled: Boolean) { context.dataStore.edit { it[CLIPBOARD_MONITOR] = enabled } }

    suspend fun setAppLockPin(pin: String) {
        val salt = PinHasher.generateSalt()
        val hash = PinHasher.hash(pin, salt)
        context.dataStore.edit {
            it[APP_LOCK_PIN_SALT] = salt
            it[APP_LOCK_PIN_HASH] = hash
        }
    }

    suspend fun verifyAppLockPin(pin: String): Boolean {
        val prefs = context.dataStore.data.first()
        val salt = prefs[APP_LOCK_PIN_SALT] ?: return false
        val storedHash = prefs[APP_LOCK_PIN_HASH] ?: return false
        return PinHasher.hash(pin, salt) == storedHash
    }

    suspend fun clearAppLockPin() {
        context.dataStore.edit {
            it.remove(APP_LOCK_PIN_HASH)
            it.remove(APP_LOCK_PIN_SALT)
            it[APP_LOCK_ENABLED] = false
        }
    }

    suspend fun recordBackgroundTime() {
        context.dataStore.edit { it[LAST_BACKGROUND_TIME] = System.currentTimeMillis() }
    }

    suspend fun shouldRelockAfterBackground(): Boolean {
        val prefs = context.dataStore.data.first()
        val lastBackground = prefs[LAST_BACKGROUND_TIME] ?: return false
        val timeoutMinutes = prefs[AUTO_LOCK_TIMEOUT_MINUTES] ?: 0
        val elapsedMinutes = (System.currentTimeMillis() - lastBackground) / 60000
        return elapsedMinutes >= timeoutMinutes
    }

    suspend fun panicClear() {
        context.dataStore.edit { it.clear() }
    }
}

private object PinHasher {
    fun generateSalt(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hash(pin: String, salt: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray())
        val hashBytes = digest.digest(pin.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
