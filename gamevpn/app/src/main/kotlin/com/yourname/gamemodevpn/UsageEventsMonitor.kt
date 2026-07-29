package com.yourname.gamemodevpn

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Real-time game detection via UsageEvents streaming.
 * More accurate and faster than polling UsageStats every 1.5s.
 * Detects MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND events.
 */
class UsageEventsMonitor(private val ctx: Context) {

    private val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastEventTime = System.currentTimeMillis()
    private var currentForeground: String? = null

    var onGameForeground: ((String) -> Unit)? = null
    var onGameBackground: ((String) -> Unit)? = null

    private val prefs = ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)

    companion object { const val TAG = "UsageEventsMonitor"; const val POLL_MS = 500L }

    fun start() {
        if (running) return
        running = true
        Log.i(TAG, "✅ UsageEvents streaming started (${POLL_MS}ms poll)")
        scheduleNext()
    }

    private fun scheduleNext() {
        if (!running) return
        handler.postDelayed({
            checkEvents()
            scheduleNext()
        }, POLL_MS)
    }

    private fun checkEvents() {
        try {
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(lastEventTime, now)
            val event = UsageEvents.Event()
            val games = prefs.getStringSet("selected_games", emptySet()) ?: emptySet()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName

                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (games.contains(pkg) && currentForeground != pkg) {
                            currentForeground = pkg
                            Log.i(TAG, "🎮 Game foreground: $pkg")
                            handler.post { onGameForeground?.invoke(pkg) }
                        }
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (games.contains(pkg) && currentForeground == pkg) {
                            currentForeground = null
                            Log.i(TAG, "🎮 Game background: $pkg")
                            handler.post { onGameBackground?.invoke(pkg) }
                        }
                    }
                }
            }
            lastEventTime = now
        } catch (e: Exception) { Log.w(TAG, e.message ?: "") }
    }

    fun stop() { running = false; handler.removeCallbacksAndMessages(null) }
    fun getCurrentGame() = currentForeground
}
