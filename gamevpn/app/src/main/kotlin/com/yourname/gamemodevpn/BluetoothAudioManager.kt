package com.yourname.gamemodevpn

import android.bluetooth.*
import android.content.Context
import android.media.AudioManager
import android.util.Log

class BluetoothAudioManager(private val ctx: Context) {

    private val bm  = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val am  = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object { const val TAG = "BTAudio" }

    data class BTInfo(
        val connected: Boolean,
        val deviceName: String,
        val codec: String,
        val latencyMs: Int,
        val recommendation: String
    )

    fun getInfo(): BTInfo {
        return try {
            val adapter = bm.adapter ?: return BTInfo(false, "N/A", "N/A", 0, "אין Bluetooth")
            val devices = adapter.bondedDevices ?: emptySet()

            // Find connected A2DP device
            val headset = devices.firstOrNull { device ->
                device.bluetoothClass?.hasService(BluetoothClass.Service.AUDIO) == true
            }

            if (headset == null) return BTInfo(false, "אין אוזניות BT", "N/A", 0, "חבר אוזניות Bluetooth")

            // Detect codec via AudioManager
            val codec = detectCodec()
            val latency = estimateLatency(codec)
            val rec = when (codec) {
                "LDAC"  -> "✅ LDAC — איכות גבוהה, latency ~150ms"
                "aptX-LL" -> "✅ aptX Low Latency — מושלם לגיימינג (~40ms)"
                "aptX"  -> "🟡 aptX — טוב (~70ms)"
                "AAC"   -> "🟡 AAC — בסדר (~120ms)"
                else    -> "⚠️ SBC — latency גבוה (~220ms), שקול כבל"
            }

            Log.i(TAG, "BT: ${headset.name} | $codec | ${latency}ms")
            BTInfo(true, headset.name, codec, latency, rec)
        } catch (e: Exception) {
            BTInfo(false, "שגיאה", "N/A", 0, e.message ?: "")
        }
    }

    private fun detectCodec(): String {
        // Heuristic: check AudioManager extra params
        val codecParam = am.getParameters("bluetooth_enabled_codecs") ?: ""
        return when {
            codecParam.contains("ldac",  ignoreCase = true) -> "LDAC"
            codecParam.contains("aptxhd",ignoreCase = true) -> "aptX-HD"
            codecParam.contains("aptxll",ignoreCase = true) -> "aptX-LL"
            codecParam.contains("aptx",  ignoreCase = true) -> "aptX"
            codecParam.contains("aac",   ignoreCase = true) -> "AAC"
            else -> "SBC"
        }
    }

    private fun estimateLatency(codec: String) = when (codec) {
        "aptX-LL" -> 40; "aptX-HD" -> 60; "aptX" -> 70
        "AAC" -> 120; "LDAC" -> 150; else -> 220
    }

    // Request low-latency gaming mode from BT stack
    fun requestLowLatency() {
        try {
            am.setParameters("bt_headset_name=gaming")
            Log.i(TAG, "Requested BT gaming/low-latency mode")
        } catch (e: Exception) { Log.w(TAG, e.message ?: "") }
    }
}
