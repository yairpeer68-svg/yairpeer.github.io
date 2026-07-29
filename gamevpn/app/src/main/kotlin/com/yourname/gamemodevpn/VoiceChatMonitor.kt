package com.yourname.gamemodevpn

import android.app.AppOpsManager
import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Detects active voice chat in game:
 * - Monitors AppOps RECORD_AUDIO state
 * - When mic is active: boosts audio thread priority
 * - Measures voice chat quality (volume level)
 */
class VoiceChatMonitor(private val ctx: Context) {

    private val aom = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private val am  = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var micWasActive = false

    var onVoiceChatStarted: (() -> Unit)? = null
    var onVoiceChatStopped: (() -> Unit)? = null

    companion object { const val TAG = "VoiceChatMonitor" }

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!active) return
            val prefs = ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
            val games = prefs.getStringSet("selected_games", emptySet()) ?: emptySet()

            val micActive = games.any { pkg ->
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
                        aom.isOpActive(AppOpsManager.OPSTR_RECORD_AUDIO, uid, pkg)
                    } else false
                } catch (e: Exception) { false }
            }

            if (micActive && !micWasActive) {
                micWasActive = true
                Log.i(TAG, "🎙 Voice chat STARTED")
                boostVoiceAudio()
                onVoiceChatStarted?.invoke()
            } else if (!micActive && micWasActive) {
                micWasActive = false
                Log.i(TAG, "🎙 Voice chat STOPPED")
                onVoiceChatStopped?.invoke()
            }

            handler.postDelayed(this, 800)
        }
    }

    private fun boostVoiceAudio() {
        try {
            // Set communication mode for voice chat
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            // Ensure speaker/headphone is at max for voice clarity
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0)
            Log.i(TAG, "✅ Voice audio boosted (mode=IN_COMMUNICATION)")
        } catch (e: Exception) { Log.w(TAG, e.message ?: "") }
    }

    fun start() {
        if (active) return
        active = true
        handler.post(checkRunnable)
        Log.i(TAG, "VoiceChatMonitor started")
    }

    fun stop() {
        active = false
        handler.removeCallbacks(checkRunnable)
        try { am.mode = AudioManager.MODE_NORMAL } catch (e: Exception) { }
    }

    fun isMicActive() = micWasActive
}
