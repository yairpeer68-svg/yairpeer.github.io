package com.yourname.gamemodevpn

import android.media.AudioManager
import android.media.audiofx.*
import android.util.Log

/**
 * Gaming audio EQ:
 * - Boost 2-4kHz (footsteps, gunshots)
 * - Loudness Enhancer for headphones
 * - Custom EQ presets
 */
class AudioEffectManager {

    private var equalizer: Equalizer? = null
    private var loudness: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var active = false

    companion object {
        const val TAG = "AudioEffect"
        // EQ presets for gaming
        val PRESET_FPS = shortArrayOf(   // Boost highs (footsteps, shots)
            -200, -100, 0, 300, 500      // 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz
        )
        val PRESET_BALANCED = shortArrayOf(0, 0, 0, 0, 0)
        val PRESET_BASS = shortArrayOf(500, 300, 0, 0, 0)
    }

    fun activate(audioSessionId: Int = 0) {
        if (active) return
        try {
            // Equalizer
            equalizer = Equalizer(0, audioSessionId).also { eq ->
                eq.enabled = true
                applyEqPreset(PRESET_FPS, eq)
                Log.i(TAG, "✅ EQ active: ${eq.numberOfBands} bands")
            }

            // Loudness Enhancer (+600mB for clarity)
            loudness = LoudnessEnhancer(audioSessionId).also {
                it.setTargetGain(600) // millibels
                it.enabled = true
                Log.i(TAG, "✅ LoudnessEnhancer: +600mB")
            }

            // Bass Boost (moderate)
            bassBoost = BassBoost(0, audioSessionId).also {
                it.setStrength(300)
                it.enabled = true
                Log.i(TAG, "✅ BassBoost: 300/1000")
            }

            // Virtualizer (surround sound for headphones)
            virtualizer = Virtualizer(0, audioSessionId).also {
                it.setStrength(500)
                it.enabled = true
                Log.i(TAG, "✅ Virtualizer: surround sound")
            }

            active = true
        } catch (e: Exception) {
            Log.w(TAG, "AudioEffect error: ${e.message}")
        }
    }

    fun applyEqPreset(preset: ShortArray, eq: Equalizer? = equalizer) {
        val e = eq ?: return
        try {
            val bands = e.numberOfBands.toInt()
            preset.take(bands).forEachIndexed { i, gain ->
                e.setBandLevel(i.toShort(), gain)
            }
            Log.i(TAG, "EQ preset applied: ${preset.toList()}")
        } catch (ex: Exception) { Log.w(TAG, "EQ: ${ex.message}") }
    }

    fun setLoudnessGain(mB: Int) {
        try { loudness?.setTargetGain(mB.coerceIn(0, 1000)) } catch (e: Exception) { }
    }

    fun deactivate() {
        try { equalizer?.release();  equalizer = null  } catch (e: Exception) { }
        try { loudness?.release();   loudness = null   } catch (e: Exception) { }
        try { bassBoost?.release();  bassBoost = null  } catch (e: Exception) { }
        try { virtualizer?.release();virtualizer = null} catch (e: Exception) { }
        active = false
        Log.i(TAG, "AudioEffects released")
    }

    fun getEqBandInfo(): List<Triple<Int, Int, Short>> {
        val eq = equalizer ?: return emptyList()
        return (0 until eq.numberOfBands).map { i ->
            val range = eq.getBandFreqRange(i.toShort())
            Triple(range[0] / 1000, range[1] / 1000, eq.getBandLevel(i.toShort()))
        }
    }

    fun isActive() = active
}
