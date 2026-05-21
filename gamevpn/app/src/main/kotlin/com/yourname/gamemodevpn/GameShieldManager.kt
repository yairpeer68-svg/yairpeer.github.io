package com.yourname.gamemodevpn

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Manages DND (Do Not Disturb) + Audio Focus hijacking for gaming.
 * Activated when a game is detected in foreground.
 */
class GameShieldManager(private val ctx: Context) {

    private val nm  = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val am  = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isShielded = false

    companion object { const val TAG = "GameShield" }

    // ── DND ──────────────────────────────────────────────────────────────────
    fun enableDnd() {
        if (!nm.isNotificationPolicyAccessGranted) {
            Log.w(TAG, "DND permission not granted yet")
            return
        }
        try {
            // INTERRUPTION_FILTER_NONE = total silence, not even alarms
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            Log.i(TAG, "🔕 DND enabled — total silence")
        } catch (e: Exception) { Log.e(TAG, "DND error: ${e.message}") }
    }

    fun disableDnd() {
        if (!nm.isNotificationPolicyAccessGranted) return
        try {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            Log.i(TAG, "🔔 DND disabled — notifications restored")
        } catch (e: Exception) { Log.e(TAG, "DND restore error: ${e.message}") }
    }

    // ── Audio Focus ───────────────────────────────────────────────────────────
    fun hijackAudioFocus() {
        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            // If something tries to steal our focus, immediately re-request it
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                Log.w(TAG, "Audio focus challenged — reclaiming!")
                requestFocus()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(listener)
                .build()
        }
        requestFocus()
    }

    private fun requestFocus() {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            am.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.i(TAG, "🎧 Audio focus hijacked — background audio blocked")
        } else {
            Log.w(TAG, "Audio focus request failed (result=$result)")
        }
    }

    fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            am.abandonAudioFocusRequest(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        audioFocusRequest = null
        Log.i(TAG, "🎧 Audio focus released")
    }

    // ── All shields ───────────────────────────────────────────────────────────
    fun activateAll() {
        if (isShielded) return
        enableDnd()
        hijackAudioFocus()
        isShielded = true
        Log.i(TAG, "🛡 All shields ACTIVE")
    }

    fun deactivateAll() {
        if (!isShielded) return
        disableDnd()
        releaseAudioFocus()
        isShielded = false
        Log.i(TAG, "🛡 All shields INACTIVE")
    }

    fun isDndGranted() = nm.isNotificationPolicyAccessGranted
}
