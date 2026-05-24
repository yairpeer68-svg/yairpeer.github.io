package com.yourname.gamemodevpn

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.gamemodevpn.wear.WearDataSender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class GameViewModel(private val app: Application) : AndroidViewModel(app) {

    // ── UI State ──────────────────────────────────────────────────────────────
    data class UiState(
        val isActive: Boolean = false,
        val pingMs: Int = 0,
        val lossPercent: Float = 0f,
        val cpuPercent: Int = 0,
        val availRamMb: Long = 0,
        val tempC: Float = 0f,
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
        val rxKbps: Float = 0f,
        val txKbps: Float = 0f,
        val sessionAvgPing: Int = 0,
        val sessionLoss: Float = 0f
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ── Managers (owned by ViewModel — survive rotation) ─────────────────────
    private val ctx: Context get() = app.applicationContext
    private val prefs by lazy { ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE) }

    val shield        by lazy { GameShieldManager(ctx) }
    val thermal       by lazy { ThermalMonitor(ctx) }
    val profileMgr    by lazy { ProfileManager(ctx) }
    val perGameMgr    by lazy { PerGameProfileManager(ctx) }
    val db            by lazy { SessionDatabase(ctx) }
    val resourceMgr   by lazy { GameResourceManager(ctx) }
    val networkMon    by lazy { NetworkMonitor(ctx) }
    val gameApi       by lazy { GameApiManager(ctx) }
    val displayMgr    by lazy { DisplayBoostManager(ctx) }
    val audioFx       by lazy { AudioEffectManager() }
    val pingPredictor by lazy { PingPredictor() }
    val gyro          by lazy { GyroManager(ctx) }
    val battery       by lazy { BatteryAwareManager(ctx) }
    val cell          by lazy { CellMonitor(ctx) }
    val analytics     by lazy { NetworkAnalytics(ctx) }
    val multiPath     by lazy { MultiPathManager(ctx) }
    val adaptive      by lazy { AdaptiveLearner(ctx) }
    val liveNotif     by lazy { LiveNotificationManager(ctx).also { it.init() } }
    val btAudio       by lazy { BluetoothAudioManager(ctx) }
    val hwMonitor     by lazy { HardwareMonitor(ctx) }
    val appOps        by lazy { AppOpsBlocker(ctx) }
    val roamingGuard  by lazy { RoamingGuard(ctx) }
    val maintenance   by lazy { MaintenanceScheduler(ctx) }
    val voiceChat     by lazy { VoiceChatMonitor(ctx) }
    val framePacing   by lazy { FramePacingMonitor() }
    val antiLag       by lazy { AntiLagManager(ctx) }
    val bandwidthMon  by lazy { BandwidthMonitor() }
    val usageMonitor  by lazy { UsageEventsMonitor(ctx) }

    private var sessionStats: SessionStats = SessionStats(ctx, "Game")

    // ── Internal flows ────────────────────────────────────────────────────────
    private val _livePingMs = MutableStateFlow(0)
    private var pingJob: Job? = null
    private var statsJob: Job? = null

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
        startBackgroundServices()
        loadSelectedGames()
    }

    private fun startBackgroundServices() {
        networkMon.start(); battery.start(); gameApi.startThermalMonitoring()
        gyro.start(); roamingGuard.start(); voiceChat.start(); framePacing.start()
        maintenance.scheduleDailyMaintenance(); maintenance.scheduleNetworkCheck()
        DoHResolver.preferDoT = prefs.getBoolean("use_dot", false)

        bandwidthMon.onSample = { s ->
            _uiState.update { it.copy(rxKbps = s.rxKbps, txKbps = s.txKbps) }
        }
        bandwidthMon.start()

        usageMonitor.onGameForeground = { pkg ->
            if (prefs.getBoolean("anti_lag", true)) antiLag.triggerForGame(pkg)
        }
        usageMonitor.onGameBackground = { _ -> antiLag.reset() }
        usageMonitor.start()
    }

    fun loadSelectedGames() {
        val games = prefs.getStringSet("selected_games", emptySet()) ?: emptySet()
        _uiState.update { it.copy(selectedGames = games) }
    }

    // ── Ping measurement (replaces Activity pingJob) ──────────────────────────
    fun startPingMeasurement() {
        pingJob?.cancel()
        pingJob = viewModelScope.launch(Dispatchers.IO) {
            val fallbacks = listOf("1.1.1.1" to 53, "8.8.8.8" to 53)
            var fbIdx = 0
            while (isActive) {
                try {
                    val gameServer = AutoServerSelector.getBestServer()
                    val ms: Long = if (gameServer != null) {
                        HappyEyeballs.measureLatency(gameServer.host, gameServer.port)
                            .takeIf { it > 0 } ?: tcpPing(gameServer.host, gameServer.port)
                    } else {
                        val (host, port) = fallbacks[fbIdx % fallbacks.size]
                        fbIdx++
                        HappyEyeballs.measureLatency(host, port)
                    }
                    if (ms > 0) _livePingMs.value = ms.toInt().coerceIn(1, 9999)
                } catch (_: Exception) { }
                delay(900)
            }
        }
    }

    fun stopPingMeasurement() {
        pingJob?.cancel(); pingJob = null
        _livePingMs.value = 0
    }

    // ── Stats loop (replaces Handler statsUpdater Runnable) ───────────────────
    fun startStatsCollection() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            _livePingMs
                .filter { it > 0 }
                .collect { ping ->
                    sessionStats.recordPing(ping)
                    pingPredictor.addSample(ping)
                    val pred   = pingPredictor.predict()
                    val cpu    = resourceMgr.getProcessBoostManager().getCpuUsagePercent()
                    val mem    = resourceMgr.getProcessBoostManager().getMemoryInfo()
                    val temp   = thermal.getTemperature()
                    val loss   = PacketEngine.getPacketLoss()
                    val jitter = sessionStats.getCurrentJitter()
                    val score  = adaptive.computeScore(ping, loss, jitter, temp)
                    val fps    = framePacing.getStats()
                    val bat    = battery.getBatteryPercent()

                    _uiState.update { s ->
                        s.copy(
                            pingMs           = ping,
                            lossPercent      = loss,
                            cpuPercent       = cpu,
                            availRamMb       = mem.availMb.toLong(),
                            tempC            = temp,
                            scoreLabel       = adaptive.getScoreLabel(score),
                            avgFps           = fps.avgFps,
                            jankPercent      = fps.jankPercent,
                            batteryPercent   = bat,
                            isCharging       = battery.isCharging(),
                            predictionMessage = pred.message,
                            spikeWarning     = pred.spikeWarning,
                            pingHistory      = sessionStats.getLivePingHistory(),
                            adaptiveThreshold = adaptive.getAdaptiveThreshold()
                        )
                    }

                    adaptive.saveLearnedData(ping, temp)
                    liveNotif.update(ping, loss, cpu, temp, "Game")
                    GameBoostWidget.updateAllWidgets(ctx)

                    if (prefs.getBoolean("wear_sync", false))
                        WearDataSender.pushPingUpdate(ctx, ping, true)

                    delay(200) // throttle — don't recompose faster than 5fps
                }
        }
    }

    fun stopStatsCollection() {
        statsJob?.cancel(); statsJob = null
    }

    // ── Game mode start ───────────────────────────────────────────────────────
    fun launch(vpnIntent: Intent? = null) {
        val pkgs = ArrayList(
            prefs.getStringSet("selected_games", null) ?: setOf("com.activision.callofduty.shooter")
        )
        val firstPkg = pkgs.first()
        perGameMgr.applyToPrefs(firstPkg, prefs)
        DnsPrefetcher.prefetch(if (firstPkg.contains("activision")) "CoD Mobile" else "PUBG Mobile")
        if (prefs.getBoolean("rst_burst", true)) {
            ctx.startService(Intent(ctx, GameModeVpnService::class.java).apply {
                action = GameModeVpnService.ACTION_RESET_CONNECTIONS
            })
        }
        ctx.startService(Intent(ctx, GameModeVpnService::class.java).apply {
            putStringArrayListExtra(GameModeVpnService.EXTRA_PACKAGES, pkgs)
        })
        if (prefs.getBoolean("cpu_boost", true)) resourceMgr.activate(pkgs.toSet())
        if (prefs.getBoolean("mptcp", true)) multiPath.activateAll()
        if (prefs.getBoolean("dnd", true)) shield.activateAll()
        if (prefs.getBoolean("thermal", true)) thermal.start()
        if (prefs.getBoolean("audio_eq", true)) audioFx.activate()
        if (prefs.getBoolean("hz_lock", true)) displayMgr.setMaxRefreshRate(ctx)
        gameApi.reportGameplayStarted(firstPkg)

        viewModelScope.launch(Dispatchers.IO) {
            delay(2000)
            val pid = resourceMgr.getProcessBoostManager().findGamePid(firstPkg)
            if (pid != null) PacketEngine.adviseKeepInRam(pid)
        }

        PacketEngine.resetCounters(); pingPredictor.reset(); framePacing.reset()
        sessionStats = SessionStats(ctx, firstPkg); sessionStats.start()
        startPingMeasurement(); startStatsCollection()
        _uiState.update { it.copy(isActive = true) }
    }

    // ── Game mode stop ────────────────────────────────────────────────────────
    fun stop() {
        ctx.startService(Intent(ctx, GameModeVpnService::class.java).apply {
            action = GameModeVpnService.ACTION_STOP
        })
        resourceMgr.deactivate(); shield.deactivateAll(); thermal.stop()
        audioFx.deactivate(); displayMgr.clearMaxRefreshRate(ctx); multiPath.deactivateAll()
        antiLag.reset(); liveNotif.cancel()
        val rec = sessionStats.finish()
        stopPingMeasurement(); stopStatsCollection()
        _uiState.update { s ->
            s.copy(
                isActive      = false,
                pingMs        = 0,
                sessionAvgPing = rec?.avgPing ?: 0,
                sessionLoss   = rec?.packetLoss ?: 0f
            )
        }
        GameBoostWidget.updateAllWidgets(ctx)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun tcpPing(host: String, port: Int): Long = try {
        val t0 = System.currentTimeMillis()
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 2000) }
        System.currentTimeMillis() - t0
    } catch (_: Exception) { -1L }

    fun needsVpnPermission(): Boolean = VpnService.prepare(ctx) != null
    fun getVpnPermissionIntent(): Intent? = VpnService.prepare(ctx)

    fun saveGames(pkgs: Set<String>) {
        prefs.edit().putStringSet("selected_games", pkgs).apply()
        _uiState.update { it.copy(selectedGames = pkgs) }
    }

    fun getPref(key: String, def: Boolean = false) = prefs.getBoolean(key, def)
    fun setPref(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun getPrefInt(key: String, def: Int = 0) = prefs.getInt(key, def)
    fun setPrefInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    // ── Cleanup ───────────────────────────────────────────────────────────────
    override fun onCleared() {
        stopPingMeasurement(); stopStatsCollection()
        networkMon.stop(); battery.stop(); gyro.stop()
        gameApi.stopThermalMonitoring(); roamingGuard.stop()
        voiceChat.stop(); framePacing.stop()
        bandwidthMon.stop(); usageMonitor.stop(); antiLag.destroy()
        super.onCleared()
    }
}
