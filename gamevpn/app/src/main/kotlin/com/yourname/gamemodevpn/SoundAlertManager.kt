package com.yourname.gamemodevpn

import android.content.Context
import android.media.*
import android.os.Build
import android.util.Log

/**
 * Custom sound alerts for gaming events:
 * - Ping spike: urgent beep
 * - Game mode ON: ascending tones
 * - Game mode OFF: descending tones
 * - Low battery: warning tone
 * Generated programmatically — no audio files needed
 */
object SoundAlertManager {

    private const val TAG = "SoundAlert"
    private const val SAMPLE_RATE = 44100

    enum class AlertType { ACTIVATE, DEACTIVATE, SPIKE, LOW_BATTERY, CONNECTED }

    fun play(ctx: Context, type: AlertType) {
        if (!isEnabled(ctx)) return
        Thread {
            try {
                val (freqs, durations) = when (type) {
                    AlertType.ACTIVATE    -> Pair(listOf(440, 550, 660), listOf(80, 80, 120))
                    AlertType.DEACTIVATE  -> Pair(listOf(660, 550, 440), listOf(80, 80, 120))
                    AlertType.SPIKE       -> Pair(listOf(880, 880),      listOf(60, 60))
                    AlertType.LOW_BATTERY -> Pair(listOf(330, 330, 330), listOf(100, 50, 200))
                    AlertType.CONNECTED   -> Pair(listOf(523, 659),      listOf(80, 120))
                }
                for (i in freqs.indices) {
                    playTone(freqs[i], durations[i])
                    Thread.sleep(20)
                }
            } catch (e: Exception) { Log.w(TAG, e.message ?: "") }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun playTone(freqHz: Int, durationMs: Int) {
        val samples = durationMs * SAMPLE_RATE / 1000
        val buf = ShortArray(samples)
        for (i in 0 until samples) {
            val angle = 2.0 * Math.PI * freqHz * i / SAMPLE_RATE
            // Apply fade in/out envelope
            val envelope = when {
                i < samples * 0.1 -> i.toDouble() / (samples * 0.1)
                i > samples * 0.9 -> (samples - i).toDouble() / (samples * 0.1)
                else -> 1.0
            }
            buf[i] = (Math.sin(angle) * envelope * Short.MAX_VALUE * 0.5).toInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(buf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC).build()

        track.write(buf, 0, buf.size)
        track.play()
        Thread.sleep(durationMs.toLong())
        track.stop(); track.release()
    }

    private fun isEnabled(ctx: Context) =
        ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
            .getBoolean("sound_alerts", true)
}
