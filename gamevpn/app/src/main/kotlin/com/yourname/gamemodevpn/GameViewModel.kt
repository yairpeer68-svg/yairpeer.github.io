package com.yourname.gamemodevpn

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds all UI state for MainActivity.
 * Survives configuration changes (theme toggle, screen rotation)
 * without recreating managers.
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val isActive: Boolean = false,
        val pingMs: Int = 0,
        val lossPercent: Float = 0f,
        val cpuPercent: Int = 0,
        val availRamMb: Long = 0,
        val tempC: Float = 0f,
        val score: Int = 0,
        val scoreLabel: String = "—",
        val avgFps: Float = 0f,
        val jankPercent: Float = 0f,
        val batteryPercent: Int = 100,
        val isCharging: Boolean = false,
        val predictionMessage: String = "ML Predictor: אין נתונים",
        val spikeWarning: Boolean = false,
        val pingHistory: List<Int> = emptyList(),
        val adaptiveThreshold: Int = 80,
        val selectedGames: Set<String> = emptySet(),
        val bestServer: GameServer? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _livePingMs = MutableStateFlow(0)
    val livePingMs: StateFlow<Int> = _livePingMs.asStateFlow()

    @Volatile var isVpnActive: Boolean = false
    private var pingJob: Job? = null

    fun startPingMeasurement() {
        pingJob?.cancel()
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            val targets = listOf("1.1.1.1", "8.8.8.8")
            var idx = 0
            while (true) {
                try {
                    val gameServer = AutoServerSelector.getBestServer()
                    val pingMs = if (gameServer != null) {
                        tcpPing(gameServer.host, gameServer.port)
                    } else {
                        val host = targets[idx % targets.size]
                        idx++
                        IcmpPinger.ping(host, 2000).takeIf { it.reachable }?.pingMs?.toInt()
                    }
                    if (pingMs != null && pingMs > 0) {
                        _livePingMs.value = pingMs.coerceIn(1, 9999)
                    }
                } catch (_: Exception) { }
                delay(900)
            }
        }
    }

    fun stopPingMeasurement() {
        pingJob?.cancel()
        pingJob = null
        _livePingMs.value = 0
    }

    fun updateUiState(block: UiState.() -> UiState) {
        _uiState.value = _uiState.value.block()
    }

    private fun tcpPing(host: String, port: Int): Int? = runCatching {
        val start = System.currentTimeMillis()
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 2000) }
        (System.currentTimeMillis() - start).toInt()
    }.getOrNull()

    override fun onCleared() {
        stopPingMeasurement()
        super.onCleared()
    }
}
