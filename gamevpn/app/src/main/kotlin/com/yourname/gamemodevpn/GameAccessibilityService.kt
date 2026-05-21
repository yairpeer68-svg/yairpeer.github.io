package com.yourname.gamemodevpn

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class GameAccessibilityService : AccessibilityService() {

    private var gameActive = false
    private val prefs by lazy { getSharedPreferences("gameboost", Context.MODE_PRIVATE) }

    companion object {
        const val TAG = "GameA11y"
        var instance: GameAccessibilityService? = null
        fun setGameActive(active: Boolean) { instance?.gameActive = active }
    }

    override fun onServiceConnected() {
        instance = this
        Log.d(TAG, "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val selectedPkgs = prefs.getStringSet("selected_games", emptySet()) ?: emptySet()

        when (event.eventType) {
            // משחק עלה לחזית
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (selectedPkgs.contains(pkg)) {
                    if (!gameActive) {
                        gameActive = true
                        onGameStarted()
                    }
                } else if (gameActive) {
                    gameActive = false
                    onGameStopped()
                }
            }
            // התראה מוקפצת
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (gameActive) suppressNotification(event)
            }
        }
    }

    private fun onGameStarted() {
        Log.d(TAG, "🎮 Game detected - activating shields")
        // השתקת כל ההתראות
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            }
        } catch (e: Exception) { Log.e(TAG, e.message ?: "") }
    }

    private fun onGameStopped() {
        Log.d(TAG, "🎮 Game closed - restoring notifications")
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (e: Exception) { Log.e(TAG, e.message ?: "") }
    }

    private fun suppressNotification(event: AccessibilityEvent) {
        // מחזירים את החלון למשחק אם נחטף
        val rootNode = rootInActiveWindow ?: return
        val pkg = rootNode.packageName?.toString() ?: return
        val selectedPkgs = prefs.getStringSet("selected_games", emptySet()) ?: emptySet()
        if (!selectedPkgs.contains(pkg)) {
            Log.d(TAG, "Suppressing notification from: $pkg")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    override fun onInterrupt() {
        instance = null
        Log.d(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
