package com.yourname.gamemodevpn

import android.content.Context
import android.os.Build
import android.os.HardwarePropertiesManager
import android.util.Log

class HardwareMonitor(private val ctx: Context) {

    companion object { const val TAG = "HardwareMonitor" }

    data class HardwareStats(
        val cpuTemps: List<Float>,
        val gpuTemp: Float,
        val skinTemp: Float,
        val avgCpuTemp: Float,
        val hottest: Float,
        val throttling: Boolean
    )

    fun getStats(): HardwareStats {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return HardwareStats(emptyList(), 0f, 0f, 0f, 0f, false)
        }
        return try {
            val hpm = ctx.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE) as HardwarePropertiesManager

            // CPU temperatures per core
            val cpuTemps = try {
                hpm.getDeviceTemperatures(
                    HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT
                ).toList()
            } catch (e: Exception) { emptyList() }

            // GPU temperature
            val gpuTemp = try {
                hpm.getDeviceTemperatures(
                    HardwarePropertiesManager.DEVICE_TEMPERATURE_GPU,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT
                ).firstOrNull() ?: 0f
            } catch (e: Exception) { 0f }

            // Skin temperature (surface)
            val skinTemp = try {
                hpm.getDeviceTemperatures(
                    HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT
                ).firstOrNull() ?: 0f
            } catch (e: Exception) { 0f }

            val avgCpu = if (cpuTemps.isNotEmpty()) cpuTemps.average().toFloat() else 0f
            val hottest = maxOf(avgCpu, gpuTemp, skinTemp)
            val throttling = hottest > 85f || cpuTemps.any { it > 90f }

            Log.d(TAG, "CPU: ${avgCpu.toInt()}° | GPU: ${gpuTemp.toInt()}° | Skin: ${skinTemp.toInt()}°")
            HardwareStats(cpuTemps, gpuTemp, skinTemp, avgCpu, hottest, throttling)
        } catch (e: Exception) {
            Log.w(TAG, "HardwarePropertiesManager: ${e.message}")
            HardwareStats(emptyList(), 0f, 0f, 0f, 0f, false)
        }
    }

    fun getThrottlingLabel(stats: HardwareStats) = when {
        stats.throttling         -> "🔴 Throttling! (${stats.hottest.toInt()}°C)"
        stats.hottest > 75f      -> "🟠 חם (${stats.hottest.toInt()}°C)"
        stats.hottest > 60f      -> "🟡 פושר (${stats.hottest.toInt()}°C)"
        stats.hottest > 0f       -> "🟢 קריר (${stats.hottest.toInt()}°C)"
        else                     -> "❓ לא זמין"
    }
}
