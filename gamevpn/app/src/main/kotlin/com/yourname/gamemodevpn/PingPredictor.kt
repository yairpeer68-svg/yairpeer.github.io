package com.yourname.gamemodevpn

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Online ping forecasting using adaptive EMA + trend detection.
 * - Adaptive alpha: reacts faster when network is noisy
 * - Spike detection: trend + absolute + coefficient of variation
 * - Confidence score based on sample size and variance
 */
class PingPredictor {

    private val history = ArrayDeque<Float>(120)
    private var ema = 0f
    private var emaTrend = 0f
    private val baseAlpha = 0.15f
    private var initialized = false

    companion object {
        const val TAG = "PingPredictor"
        const val SPIKE_TREND_THRESHOLD = 8f
        const val SPIKE_ABS_THRESHOLD = 80f
    }

    data class Prediction(
        val predicted: Int,
        val trend: Float,
        val confidence: Float,
        val spikeWarning: Boolean,
        val message: String
    )

    fun addSample(ping: Int) {
        val p = ping.toFloat()
        if (!initialized) {
            ema = p; emaTrend = 0f; initialized = true
        } else {
            // Adaptive alpha: increase sensitivity when variance is high
            val adaptiveAlpha = if (history.size > 10) {
                val recent = history.takeLast(10)
                val mean = recent.average().toFloat()
                val std = sqrt(recent.map { (it - mean) * (it - mean) }.average()).toFloat()
                (baseAlpha + (std / (mean + 1f)) * 0.1f).coerceIn(0.1f, 0.4f)
            } else baseAlpha
            val prevEma = ema
            ema = adaptiveAlpha * p + (1f - adaptiveAlpha) * ema
            emaTrend = adaptiveAlpha * (ema - prevEma) + (1f - adaptiveAlpha) * emaTrend
        }
        history.addLast(p)
        if (history.size > 120) history.removeFirst()
    }

    fun predict(): Prediction {
        if (history.size < 5) return Prediction(0, 0f, 0f, false, "אין מספיק נתונים")

        val predicted = (ema + emaTrend * 3).coerceAtLeast(1f).toInt()

        val mean = history.average().toFloat()
        val variance = history.map { (it - mean) * (it - mean) }.average().toFloat()
        val std = sqrt(variance.toDouble()).toFloat()
        val cv = std / (mean + 1f)

        // Confidence: higher with more samples and lower variance
        val confidence = (1f - cv.coerceIn(0f, 1f)) * (history.size.toFloat() / 120f).coerceIn(0f, 1f)

        val spikeWarning = emaTrend > SPIKE_TREND_THRESHOLD ||
                           predicted > SPIKE_ABS_THRESHOLD ||
                           (emaTrend > SPIKE_TREND_THRESHOLD * 0.5f && cv > 0.3f)

        val message = when {
            emaTrend > SPIKE_TREND_THRESHOLD * 2 -> "⚠️ Ping עולה מהר — spike בדרך!"
            emaTrend > SPIKE_TREND_THRESHOLD      -> "📈 Ping עולה — היזהר"
            emaTrend < -SPIKE_TREND_THRESHOLD     -> "📉 Ping משתפר"
            abs(emaTrend) < 2f                    -> "✅ Ping יציב (${ema.toInt()}ms)"
            else                                  -> "~ ${predicted}ms predicted"
        }

        return Prediction(predicted, emaTrend, confidence, spikeWarning, message)
    }

    fun getStats(): Map<String, Any> {
        if (history.isEmpty()) return emptyMap()
        return mapOf(
            "ema"     to ema.toInt(),
            "trend"   to emaTrend,
            "min"     to history.min().toInt(),
            "max"     to history.max().toInt(),
            "samples" to history.size
        )
    }

    fun reset() { history.clear(); ema = 0f; emaTrend = 0f; initialized = false }
}
