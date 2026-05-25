package com.yourname.gamemodevpn

import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Detects phone orientation via accelerometer:
 * - Face-down → auto-enable DND
 * - Face-up / vertical → restore
 */
class GyroManager(private val ctx: Context) {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var isFaceDown = false
    private var lastFlipTime = 0L

    var onFaceDown: (() -> Unit)? = null
    var onFaceUp: (() -> Unit)? = null

    companion object { const val TAG = "GyroManager" }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val z = event.values[2] // gravity on Z axis
            val now = System.currentTimeMillis()

            // z < -7 = face down (phone flipped), z > 7 = face up
            val nowFaceDown = z < -7f
            if (nowFaceDown != isFaceDown && now - lastFlipTime > 1500) {
                isFaceDown = nowFaceDown
                lastFlipTime = now
                Log.i(TAG, if (isFaceDown) "📱 Face DOWN → DND" else "📱 Face UP → restore")
                handler.post { if (isFaceDown) onFaceDown?.invoke() else onFaceUp?.invoke() }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) { }
    }

    fun start() {
        if (active) return
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
        active = true
        Log.i(TAG, "✅ Gyro/Accelerometer monitoring started")
    }

    fun stop() {
        if (!active) return
        sm.unregisterListener(listener)
        active = false
    }

    fun isAvailable() = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
}
