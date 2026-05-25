package com.yourname.gamemodevpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

// ── Colours ───────────────────────────────────────────────────────────────────
private val BG      = Color(0xFF070C18)
private val CARD    = Color(0xFF0D1A2E)
private val ACCENT  = Color(0xFF00C8FF)
private val GREEN   = Color(0xFF00FFAA)
private val RED     = Color(0xFFFF3B6B)
private val ORANGE  = Color(0xFFFF9500)
private val TEXT    = Color(0xFFE8F4FF)
private val MUTED   = Color(0xFF4A6A8A)

class MainActivity : ComponentActivity() {

    private val vm: GameViewModel by viewModels()

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.launch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this))
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        ShortcutHelper.register(this, vm.uiState.value.selectedGames)
        if (intent?.action == "ACTION_QUICK_LAUNCH") {
            lifecycleScope.launch { delay(500); startGame() }
        }
        setContent {
            GameBoostTheme {
                val state by vm.uiState.collectAsStateWithLifecycle()
                GameBoostApp(state = state, vm = vm, onStartGame = ::startGame)
            }
        }
    }

    private fun startGame() {
        val intent = vm.getVpnPermissionIntent()
        if (intent != null) vpnLauncher.launch(intent)
        else vm.launch()
    }

    override fun onResume() { super.onResume(); vm.loadSelectedGames() }
}

// ── Theme ─────────────────────────────────────────────────────────────────────
@Composable
fun GameBoostTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BG, surface = CARD,
            primary = ACCENT, onPrimary = BG,
            onBackground = TEXT, onSurface = TEXT
        ),
        content = content
    )
}

// ── Root scaffold ─────────────────────────────────────────────────────────────
@Composable
fun GameBoostApp(state: GameViewModel.UiState, vm: GameViewModel, onStartGame: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("⚡" to "בית", "🎮" to "משחק", "🖥" to "חומרה", "📡" to "רשת", "📈" to "ניתוח", "⚙️" to "הגדרות")

    Scaffold(
        containerColor = BG,
        bottomBar = {
            NavigationBar(containerColor = CARD, contentColor = ACCENT) {
                tabs.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = selectedTab == i,
                        onClick  = { selectedTab = i },
                        icon     = { Text(icon, fontSize = 18.sp) },
                        label    = { Text(label, fontSize = 9.sp) },
                        colors   = NavigationBarItemDefaults.colors(
                            selectedIconColor   = ACCENT,
                            unselectedIconColor = MUTED,
                            indicatorColor      = CARD
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> HomeScreen(state, vm, onStartGame)
                1 -> GameScreen(state, vm)
                2 -> HwScreen(vm)
                3 -> NetScreen(state, vm)
                4 -> AnalyticsScreen(vm)
                5 -> SettingsScreen(state, vm)
            }
        }
    }
}

// ── HOME ──────────────────────────────────────────────────────────────────────
@Composable
fun HomeScreen(state: GameViewModel.UiState, vm: GameViewModel, onStartGame: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Ping Booster", color = TEXT, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            StatusPill(state.isActive)
        }
        Spacer(Modifier.height(16.dp))

        // Power card
        GameCard(Modifier.fillMaxWidth()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                AnimatedPowerBtn(isActive = state.isActive) {
                    if (state.isActive) vm.stop() else onStartGame()
                }
                Spacer(Modifier.height(8.dp))
                Text(if (state.isActive) "לחץ לעצירה" else "לחץ להפעלה", color = MUTED, fontSize = 13.sp)
                Text(state.predictionMessage, color = if (state.spikeWarning) RED else GREEN, fontSize = 10.sp)
                if (state.isActive && state.sessionAvgPing > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("avg ${state.sessionAvgPing}ms · loss ${String.format("%.1f", state.sessionLoss)}%", color = MUTED, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Stats grid
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Ping", "${state.pingMs}ms", ORANGE, Modifier.weight(1f))
            StatCard("Loss", "${String.format("%.1f", state.lossPercent)}%", RED, Modifier.weight(1f))
            StatCard("Score", state.scoreLabel, GREEN, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("CPU", "${state.cpuPercent}%", Color(0xFFA259FF), Modifier.weight(1f))
            StatCard("RAM", "${state.availRamMb}MB", ACCENT, Modifier.weight(1f))
            StatCard("Temp", "${state.tempC.toInt()}°", if (state.tempC >= 43f) RED else if (state.tempC >= 38f) ORANGE else GREEN, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("FPS", "${state.avgFps.toInt()}", if (state.avgFps < 30f) RED else if (state.avgFps < 50f) ORANGE else GREEN, Modifier.weight(1f))
            StatCard("Battery", "${state.batteryPercent}%${if (state.isCharging) " ⚡" else ""}", if (state.batteryPercent < 20) RED else if (state.batteryPercent < 50) ORANGE else GREEN, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))

        // Ping graph
        GameCard(Modifier.fillMaxWidth().height(140.dp)) {
            AndroidViewPingGraph(pings = state.pingHistory, threshold = state.adaptiveThreshold)
        }
        Spacer(Modifier.height(12.dp))

        // Profiles
        GameCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("🎮 פרופילים", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.profileMgr.defaults.forEach { p ->
                        OutlinedButton(
                            onClick = { vm.profileMgr.applyToPrefs(p, ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)); Toast.makeText(ctx,"✅ ${p.name}", Toast.LENGTH_SHORT).show() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ACCENT)
                        ) { Text(p.name, fontSize = 9.sp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Games
        GameCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎮 משחקים", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { GameAutoDetector.detect(ctx); /* refresh */ }) {
                        Text("🔍 זיהוי", color = ORANGE, fontSize = 10.sp)
                    }
                }
                Text(
                    if (state.selectedGames.isEmpty()) "לא נבחרו" else state.selectedGames.joinToString(" · ") { pkg ->
                        try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                    },
                    color = MUTED, fontSize = 11.sp
                )
            }
        }
    }
}

// ── GAME ──────────────────────────────────────────────────────────────────────
@Composable
fun GameScreen(state: GameViewModel.UiState, vm: GameViewModel) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("🎮 Per-Game Settings", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))

        if (state.selectedGames.isEmpty()) {
            Text("בחר משחקים קודם", color = MUTED, modifier = Modifier.align(Alignment.CenterHorizontally))
            return@Column
        }

        state.selectedGames.forEach { pkg ->
            val cfg = vm.perGameMgr.getConfig(pkg)
            GameCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("🎮 ${cfg.gameName}", color = ACCENT, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    listOf("MTU" to "${cfg.mtu}B", "Target FPS" to "${cfg.targetFps}", "Spike Threshold" to "${cfg.spikeThreshold}ms", "DSCP EF" to if (cfg.dscpEf) "✅" else "❌", "Region" to cfg.preferredRegion).forEach { (l, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(l, color = MUTED, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            Text(v, color = TEXT, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.perGameMgr.applyToPrefs(pkg, ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)); Toast.makeText(ctx,"✅ ${cfg.gameName}", Toast.LENGTH_SHORT).show() },
                        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = BG)) {
                        Text("החל הגדרות עבור ${cfg.gameName}", fontSize = 11.sp)
                    }
                }
            }
        }

        // Auto server
        var serverStatus by remember { mutableStateOf("לא נבדק") }
        GameCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("🌐 Auto Server Select", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Text(serverStatus, color = MUTED, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { serverStatus = "סורק..."; AutoServerSelector.selectAndApply("CoD") { r -> serverStatus = if (r.best != null) "✅ ${r.best.region} (${r.best.pingMs}ms)" else "❌ לא נמצא" } }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = BG)) {
                    Text("🔍 בחר שרת מהיר", fontSize = 12.sp)
                }
            }
        }

        // Frame pacing
        val fps = vm.framePacing.getStats()
        GameCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("📊 Frame Pacing", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Text("FPS: ${fps.avgFps.toInt()} · Jank: ${String.format("%.1f", fps.jankPercent)}% · ${fps.stability}", color = if (fps.jankPercent < 5f) GREEN else ORANGE, fontSize = 12.sp)
            }
        }
    }
}

// ── HW ────────────────────────────────────────────────────────────────────────
@Composable
fun HwScreen(vm: GameViewModel) {
    val hw = vm.hwMonitor.getStats()
    val bt = vm.btAudio.getInfo()
    Column(Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("🖥 חומרה", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))

        GameCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("🌡 טמפרטורות", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                HwRow("CPU ממוצע", "${hw.avgCpuTemp.toInt()}°C", if (hw.avgCpuTemp >= 43f) RED else GREEN)
                HwRow("GPU", "${hw.gpuTemp.toInt()}°C", if (hw.gpuTemp >= 43f) RED else GREEN)
                HwRow("Surface", "${hw.skinTemp.toInt()}°C", GREEN)
                HwRow("Throttling", if (hw.throttling) "⚠️ כן!" else "✅ לא", if (hw.throttling) RED else GREEN)
            }
        }

        GameCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("🎧 Bluetooth", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                if (bt.connected) {
                    HwRow("מכשיר", bt.deviceName, TEXT)
                    HwRow("Codec", bt.codec, ACCENT)
                    HwRow("Latency", "${bt.latencyMs}ms", if (bt.latencyMs < 80) GREEN else ORANGE)
                    Text(bt.recommendation, color = if (bt.latencyMs < 80) GREEN else ORANGE, fontSize = 11.sp)
                } else Text("אין BT מחובר", color = MUTED, fontSize = 12.sp)
            }
        }

        GameCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("⚡ ביצועים", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                val cpu = vm.resourceMgr.getCpuBoostManager()
                HwRow("PerformanceHint", if (cpu.isHintManagerAvailable()) "✅" else "❌", if (cpu.isHintManagerAvailable()) GREEN else MUTED)
                HwRow("Sustained Perf", if (cpu.isSustainedPerfSupported()) "✅" else "❌", if (cpu.isSustainedPerfSupported()) GREEN else MUTED)
                HwRow("CPU Cores", "${PacketEngine.getNumCores()}", ACCENT)
                HwRow("MPTCP", if (vm.multiPath.isMptcpAvailable()) "✅" else "❌", if (vm.multiPath.isMptcpAvailable()) GREEN else MUTED)
            }
        }

        val ctx = LocalContext.current
        Button(onClick = { val pkgs = vm.uiState.value.selectedGames.ifEmpty { setOf("com.activision.callofduty.shooter") }; vm.resourceMgr.activate(pkgs); vm.displayMgr.setMaxRefreshRate(ctx); Toast.makeText(ctx,"✅ הכל מנותב!", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = BG)) {
            Text("🚀 נתב כל המשאבים", fontSize = 13.sp)
        }
    }
}

// ── NET ───────────────────────────────────────────────────────────────────────
@Composable
fun NetScreen(state: GameViewModel.UiState, vm: GameViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var dohStatus by remember { mutableStateOf("") }
    var leakStatus by remember { mutableStateOf("") }
    var speedStatus by remember { mutableStateOf("") }
    var traceLog by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("📡 רשת", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))

        // Network info
        val wifi = vm.networkMon.getWifiInfo()
        val cell = vm.cell.getCellInfo()
        GameCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                HwRow("📶 WiFi", WiFiBandHelper.getCurrent5GhzStatus(ctx), TEXT)
                HwRow("📊 RSSI", WiFiBandHelper.getRssiQuality(wifi?.rssi ?: -100), TEXT)
                HwRow("📦 MTU", "${vm.networkMon.getActiveMtu()}B", ACCENT)
                HwRow("📱 Cell", "${cell.technology} · ${cell.operator}", ACCENT)
                Text("RX: ${state.rxKbps.toInt()} Kbps  TX: ${state.txKbps.toInt()} Kbps", color = GREEN, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // DoH
        ActionCard("🔒 DoH Test", dohStatus, if (dohStatus.startsWith("✅")) GREEN else RED) {
            dohStatus = "בודק..."
            scope.launch { val r = DoHResolver.resolve("cloudflare.com"); dohStatus = if (r.ip != null) "✅ DoH: ${r.ip} (${r.latencyMs}ms)" else "❌ DoH נכשל" }
        }

        // Leak test
        ActionCard("🔍 Leak Test", leakStatus, if (leakStatus.startsWith("✅")) GREEN else RED) {
            leakStatus = "בודק..."
            scope.launch { val r = LeakTester.run(GameModeVpnService.isRunning); leakStatus = r.summary }
        }

        // Speed test
        ActionCard("⚡ Speed Test", speedStatus, if (speedStatus.startsWith("⬇")) GREEN else MUTED) {
            speedStatus = "בודק..."
            SpeedTestManager.runTest(object : SpeedTestManager.ProgressCallback {
                override fun onDownloadProgress(p: Int, mbps: Float) { speedStatus = "⬇ $p%  ${String.format("%.1f", mbps)} Mbps" }
                override fun onUploadProgress(p: Int, mbps: Float) { speedStatus = "⬆ $p%  ${String.format("%.1f", mbps)} Mbps" }
                override fun onComplete(r: SpeedTestManager.SpeedResult) { speedStatus = "⬇ ${String.format("%.1f", r.downloadMbps)} Mbps  ⬆ ${String.format("%.1f", r.uploadMbps)} Mbps  📶 ${r.latencyMs}ms" }
                override fun onError(msg: String) { speedStatus = "❌ $msg" }
            })
        }

        // Traceroute
        Spacer(Modifier.height(8.dp))
        Text("🔍 Traceroute", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Button(onClick = { traceLog = "מבצע traceroute...\n"; TracerouteManager.trace("1.1.1.1", 443, object : TracerouteManager.TraceCallback { override fun onHop(hop: TracerouteManager.Hop) { traceLog += "${if (hop.reachable) "✅" else "  "} ${hop.hopNumber}. ${hop.host}  ${hop.latencyMs}ms\n" }; override fun onComplete(hops: List<TracerouteManager.Hop>) {} }) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = CARD, contentColor = ACCENT)) {
            Text("🌐 Traceroute 1.1.1.1", fontSize = 11.sp)
        }
        if (traceLog.isNotEmpty()) {
            Text(traceLog, color = MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ── ANALYTICS ─────────────────────────────────────────────────────────────────
@Composable
fun AnalyticsScreen(vm: GameViewModel) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("📈 אנליטיקה", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))

        GameCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("⏰ שעות הכי טובות", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                vm.analytics.getBestHours().take(4).forEach { h ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(String.format("%02d:00", h.hour), color = ACCENT, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.25f))
                        Text("${h.avgPing.toInt()}ms", color = ORANGE, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(0.25f))
                        Text(h.rating, color = GREEN, fontSize = 11.sp, modifier = Modifier.weight(0.5f))
                    }
                }
            }
        }

        // Heatmap
        GameCard(Modifier.fillMaxWidth().height(200.dp).padding(bottom = 12.dp)) {
            AndroidViewHeatmap(ctx)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { BackupManager.shareBackup(ctx) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = BG)) { Text("📁 CSV", fontSize = 11.sp) }
            Button(onClick = { BackupManager.shareBackup(ctx) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = CARD, contentColor = ACCENT)) { Text("💾 גיבוי", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(12.dp))

        vm.db.getLast(5).forEach { s ->
            GameCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Column(Modifier.padding(10.dp)) {
                    val d = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.startTime))
                    Text("$d · ${s.durationSec / 60}min", color = MUTED, fontSize = 10.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("${s.avgPing}ms", color = ORANGE, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Text("⚡${s.minPing}", color = GREEN, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        Text("💀${String.format("%.1f", s.packetLoss)}%", color = RED, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        TextButton(onClick = { SessionShareManager.shareSession(ctx, s) }, modifier = Modifier.weight(0.5f)) { Text("📤", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

// ── SETTINGS ──────────────────────────────────────────────────────────────────
@Composable
fun SettingsScreen(state: GameViewModel.UiState, vm: GameViewModel) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(BG).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("⚙️ הגדרות", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(14.dp))

        @Composable
        fun SettingSwitch(icon: String, label: String, sub: String, key: String, def: Boolean = false, action: ((Boolean) -> Unit)? = null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("$icon  $label", color = TEXT, fontSize = 12.sp)
                    Text(sub, color = MUTED, fontSize = 9.sp)
                }
                var checked by remember { mutableStateOf(vm.getPref(key, def)) }
                Switch(checked = checked, onCheckedChange = { v -> checked = v; vm.setPref(key, v); action?.invoke(v) },
                    colors = SwitchDefaults.colors(checkedThumbColor = BG, checkedTrackColor = ACCENT, uncheckedTrackColor = MUTED))
            }
            HorizontalDivider(color = CARD, thickness = 0.5.dp)
        }

        SettingSwitch("⚡","Auto-Trigger","UsageEvents streaming","auto_trigger") { on -> ctx.startService(Intent(ctx, AutoTriggerService::class.java).apply { if (!on) action = "STOP" }) }
        SettingSwitch("🌐","MPTCP","WiFi + 5G במקביל","mptcp",true) { on -> if (on) vm.multiPath.activateAll() else vm.multiPath.deactivateAll() }
        SettingSwitch("🖥","CPU Boost","WakeLock + Hint + FIFO","cpu_boost",true)
        SettingSwitch("🔴","RST Burst","קוטע TCP ברקע","rst_burst",true)
        SettingSwitch("🔵","DSCP QoS","EF priority","qos_dscp",true)
        SettingSwitch("📺","120Hz","קצב רענון מקסימלי","hz_lock",true) { on -> if (on) vm.displayMgr.setMaxRefreshRate(ctx) else vm.displayMgr.clearMaxRefreshRate(ctx) }
        SettingSwitch("🔕","DND","שקט מוחלט","dnd",true) { on -> if (on && !vm.shield.isDndGranted()) ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        SettingSwitch("🎧","Audio FX","EQ + Loudness","audio_eq",true)
        SettingSwitch("🔔","Sound Alerts","צלילי Spike/הפעלה","sound_alerts",true)
        SettingSwitch("🌡","Thermal Monitor","overlay צף","thermal",true)
        SettingSwitch("📱","Gyro DND","גריפה הפוכה = שקט","gyro_dnd",true)
        SettingSwitch("💀","Kill BG","הורג תהליכי רקע","kill_bg")
        SettingSwitch("🔢","Anti-Lag","60s resource burst","anti_lag",true)
        SettingSwitch("📌","Floating Ping","overlay ping צף","floating_ping") { on ->
            val svc = Intent(ctx, FloatingPingService::class.java)
            if (on) ctx.startService(svc) else ctx.stopService(svc)
        }
        SettingSwitch("🔐","DNS-over-TLS","DoT port 853","use_dot") { on -> DoHResolver.preferDoT = on }
        SettingSwitch("⌚","Wear OS Sync","שלח ping לשעון","wear_sync")
        SettingSwitch("🔧","FEC","Forward Error Correction — שחזור packets אבודים","fec_enabled") { on ->
            PacketEngine.enableFec(on)
        }
        SettingSwitch("🗑️","Payload Trim","זרוק packets של analytics","payload_trim") { on ->
            // stored in prefs, read by GameModeVpnService
        }

        Spacer(Modifier.height(16.dp))

        // Spike threshold
        Text("⚠️ סף Spike", color = TEXT, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        var spikeThreshold by remember { mutableIntStateOf(vm.getPrefInt("spike_threshold", 80)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Slider(value = spikeThreshold.toFloat(), onValueChange = { spikeThreshold = it.toInt(); vm.setPrefInt("spike_threshold", it.toInt()) }, valueRange = 20f..200f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = ACCENT, activeTrackColor = ACCENT))
            Text("${spikeThreshold}ms", color = ORANGE, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = CARD, contentColor = ACCENT)) {
            Text("⚙️ הגדרות מתקדמות", fontSize = 12.sp)
        }
    }
}

// ── Composable helpers ────────────────────────────────────────────────────────
@Composable
fun GameCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier, color = CARD, shape = MaterialTheme.shapes.medium) { content() }
}

@Composable
fun StatCard(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    GameCard(modifier) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            Text(label, color = MUTED, fontSize = 9.sp)
        }
    }
}

@Composable
fun HwRow(label: String, value: String, valueColor: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = MUTED, fontSize = 12.sp, modifier = Modifier.weight(0.5f))
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(0.5f))
    }
}

@Composable
fun StatusPill(isActive: Boolean) {
    Surface(color = CARD, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (isActive) GREEN else MUTED, shape = MaterialTheme.shapes.small))
            Spacer(Modifier.width(6.dp))
            Text(if (isActive) "פעיל" else "כבוי", color = if (isActive) GREEN else MUTED, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun AnimatedPowerBtn(isActive: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(160.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) GREEN.copy(alpha = 0.15f) else CARD,
            contentColor   = if (isActive) GREEN else MUTED
        )
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⏻", fontSize = 48.sp)
            Text(if (isActive) "ON" else "OFF", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ActionCard(btnLabel: String, status: String, statusColor: Color, onAction: () -> Unit) {
    GameCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = CARD, contentColor = ACCENT)) {
                Text(btnLabel, fontSize = 11.sp)
            }
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(status, color = statusColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── AndroidView wrappers for custom Views ─────────────────────────────────────
@Composable
fun AndroidViewPingGraph(pings: List<Int>, threshold: Int) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx -> PingGraphView(ctx) },
        update  = { view -> view.update(pings, threshold) },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun AndroidViewHeatmap(ctx: Context) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c -> PingHeatmapCalendarView(c).also { it.loadFromDb(c) } },
        modifier = Modifier.fillMaxSize()
    )
}
