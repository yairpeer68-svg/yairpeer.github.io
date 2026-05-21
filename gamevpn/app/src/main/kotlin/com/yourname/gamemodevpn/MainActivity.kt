package com.yourname.gamemodevpn

import android.app.Activity
import android.content.*
import android.net.VpnService
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import kotlinx.coroutines.*

class MainActivity : Activity() {

    private val APP_SEL = 2
    private var vpnReqCode = 1
    private var isActive = false
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("gameboost", Context.MODE_PRIVATE) }
    private var darkMode = true
    @Volatile private var livePingMs: Int = 0
    private var pingJob: Job? = null
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Core managers
    private lateinit var shield: GameShieldManager
    private lateinit var thermal: ThermalMonitor
    private lateinit var profileMgr: ProfileManager
    private lateinit var perGameMgr: PerGameProfileManager
    private lateinit var resourceMgr: GameResourceManager
    private lateinit var networkMon: NetworkMonitor
    private lateinit var gameApi: GameApiManager
    private lateinit var displayMgr: DisplayBoostManager
    private lateinit var audioFx: AudioEffectManager
    private lateinit var pingPredictor: PingPredictor
    private lateinit var gyro: GyroManager
    private lateinit var sessionStats: SessionStats
    private lateinit var db: SessionDatabase
    private lateinit var overlay: ResourceOverlayView
    private lateinit var battery: BatteryAwareManager
    private lateinit var cell: CellMonitor
    private lateinit var analytics: NetworkAnalytics
    private lateinit var multiPath: MultiPathManager
    private lateinit var adaptive: AdaptiveLearner
    private lateinit var liveNotif: LiveNotificationManager
    private lateinit var btAudio: BluetoothAudioManager
    private lateinit var hwMonitor: HardwareMonitor
    private lateinit var appOps: AppOpsBlocker
    private lateinit var roamingGuard: RoamingGuard
    private lateinit var maintenance: MaintenanceScheduler
    private lateinit var voiceChat: VoiceChatMonitor
    private lateinit var framePacing: FramePacingMonitor
    private lateinit var powerBtn: AnimatedPowerButton

    // UI refs
    private lateinit var statusDot: View
    private lateinit var statusLabel: TextView
    private lateinit var statPing: TextView
    private lateinit var statLoss: TextView
    private lateinit var statCpu: TextView
    private lateinit var statRam: TextView
    private lateinit var statTemp: TextView
    private lateinit var statScore: TextView
    private lateinit var statFps: TextView
    private lateinit var tvPrediction: TextView
    private lateinit var tvGames: TextView
    private lateinit var tvBattery: TextView
    private lateinit var pingGraph: PingGraphView
    private lateinit var tabContent: FrameLayout

    // Theme helpers
    private fun C(d: Int, l: Int) = if (darkMode) d else l
    private val BG     get() = C(ThemeManager.Dark.BG,    ThemeManager.Light.BG)
    private val CARD   get() = C(ThemeManager.Dark.CARD,  ThemeManager.Light.CARD)
    private val CARD2  get() = C(ThemeManager.Dark.CARD2, ThemeManager.Light.CARD2)
    private val TEXT   get() = C(ThemeManager.Dark.TEXT,  ThemeManager.Light.TEXT)
    private val MUTED  get() = C(ThemeManager.Dark.MUTED, ThemeManager.Light.MUTED)
    private val MUTED2 get() = C(ThemeManager.Dark.MUTED2,ThemeManager.Light.MUTED2)
    private val ACCENT get() = C(ThemeManager.Dark.ACCENT,ThemeManager.Light.ACCENT)
    private val GREEN  get() = C(ThemeManager.Dark.GREEN, ThemeManager.Light.GREEN)
    private val RED    get() = C(ThemeManager.Dark.RED,   ThemeManager.Light.RED)
    private val ORANGE get() = C(ThemeManager.Dark.ORANGE,ThemeManager.Light.ORANGE)

    private fun startPingMeasurement() {
        pingJob?.cancel()
        pingJob = mainScope.launch(Dispatchers.IO) {
            val fallbacks = listOf("1.1.1.1" to 53, "8.8.8.8" to 53)
            var fbIdx = 0
            while (isActive) {
                try {
                    val gameServer = AutoServerSelector.getBestServer()
                    val ms: Long = if (gameServer != null) {
                        tcpPing(gameServer.host, gameServer.port)
                    } else {
                        val (host, port) = fallbacks[fbIdx % fallbacks.size]
                        fbIdx++
                        val r = IcmpPinger.ping(host, 2000)
                        if (r.reachable) r.pingMs else -1L
                    }
                    if (ms > 0) livePingMs = ms.toInt().coerceIn(1, 9999)
                } catch (_: Exception) { }
                delay(900)
            }
        }
    }

    private fun tcpPing(host: String, port: Int): Long {
        val t0 = System.currentTimeMillis()
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), 2000)
                System.currentTimeMillis() - t0
            }
        } catch (_: Exception) { -1L }
    }

    private fun stopPingMeasurement() {
        pingJob?.cancel()
        pingJob = null
        livePingMs = 0
    }

    private val statsUpdater = object : Runnable {
        override fun run() {
            if (!isActive) return
            val ping = livePingMs.takeIf { it > 0 } ?: run {
                handler.postDelayed(this, 500)
                return
            }
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

            statPing.text  = "$ping ms"
            statLoss.text  = String.format("%.1f%%", loss)
            statCpu.text   = "$cpu%"
            statRam.text   = "${mem.availMb}MB"
            statScore.text = adaptive.getScoreLabel(score)
            statFps.text   = "${fps.avgFps.toInt()} FPS"
            statFps.setTextColor(when { fps.avgFps < 30f -> RED; fps.avgFps < 50f -> ORANGE; else -> GREEN })
            tvBattery.text = "$bat%${if (battery.isCharging()) " ⚡" else ""}"
            tvBattery.setTextColor(when { bat < 20 -> RED; bat < 50 -> ORANGE; else -> GREEN })
            if (temp > 0) {
                val tc = when { temp >= 43f -> RED; temp >= 38f -> ORANGE; else -> GREEN }
                statTemp.text = "${temp.toInt()}°"; statTemp.setTextColor(tc)
            }
            tvPrediction.text = pred.message
            tvPrediction.setTextColor(if (pred.spikeWarning) RED else GREEN)
            pingGraph.update(sessionStats.getLivePingHistory(), adaptive.getAdaptiveThreshold())
            prefs.edit().putInt("last_ping", ping).apply()
            liveNotif.update(ping, loss, cpu, temp, "Game")
            adaptive.saveLearnedData(ping, temp)
            GameBoostWidget.updateAllWidgets(this@MainActivity)
            overlay.updateStats(cpu, mem.availMb, mem.totalMb, ping, temp, PacketEngine.getNumCores())
            handler.postDelayed(this, 1100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkMode = ThemeManager.isDarkMode(this)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = BG; window.navigationBarColor = BG

        // Init all managers
        shield = GameShieldManager(this); thermal = ThermalMonitor(this)
        profileMgr = ProfileManager(this); perGameMgr = PerGameProfileManager(this)
        db = SessionDatabase(this); resourceMgr = GameResourceManager(this)
        networkMon = NetworkMonitor(this); gameApi = GameApiManager(this)
        displayMgr = DisplayBoostManager(this); audioFx = AudioEffectManager()
        pingPredictor = PingPredictor(); gyro = GyroManager(this)
        sessionStats = SessionStats(this, "Game"); overlay = ResourceOverlayView(this)
        battery = BatteryAwareManager(this); cell = CellMonitor(this)
        analytics = NetworkAnalytics(this); multiPath = MultiPathManager(this)
        adaptive = AdaptiveLearner(this); liveNotif = LiveNotificationManager(this).also { it.init() }
        btAudio = BluetoothAudioManager(this); hwMonitor = HardwareMonitor(this)
        appOps = AppOpsBlocker(this); roamingGuard = RoamingGuard(this)
        maintenance = MaintenanceScheduler(this); voiceChat = VoiceChatMonitor(this)
        framePacing = FramePacingMonitor()

        // Wire up callbacks
        networkMon.start(); battery.start(); gameApi.startThermalMonitoring()
        gyro.start(); roamingGuard.start(); voiceChat.start(); framePacing.start()

        gyro.onFaceDown = { if (isActive && prefs.getBoolean("gyro_dnd", true)) { shield.enableDnd(); HapticManager.tick(this) } }
        gyro.onFaceUp   = { if (isActive && prefs.getBoolean("gyro_dnd", true)) shield.disableDnd() }
        gameApi.onThermalStatus = { s, l -> handler.post { if (s >= 3) Toast.makeText(this, "🌡 $l", Toast.LENGTH_LONG).show() } }
        battery.onLowBattery = { handler.post { SoundAlertManager.play(this, SoundAlertManager.AlertType.LOW_BATTERY); Toast.makeText(this, "🔋 סוללה נמוכה", Toast.LENGTH_LONG).show() } }
        battery.onChargingStarted = { handler.post { if (isActive) Toast.makeText(this, "⚡ בטעינה — boost!", Toast.LENGTH_SHORT).show() } }
        roamingGuard.onRoamingDetected = { msg -> handler.post { Toast.makeText(this, "🌍 $msg", Toast.LENGTH_LONG).show() } }
        voiceChat.onVoiceChatStarted = { handler.post { Toast.makeText(this, "🎙 Voice chat — audio boosted", Toast.LENGTH_SHORT).show() } }
        framePacing.onJankDetected = { n -> handler.post { if (n >= 5) Toast.makeText(this, "⚠️ Frame drop: ${n}f", Toast.LENGTH_SHORT).show() } }
        resourceMgr.onStatsUpdate = { s -> handler.post { if (::statCpu.isInitialized) { statCpu.text = "${s.cpuPercent}%"; statRam.text = "${s.availRamMb}MB" } } }
        sessionStats.onSpikeDetected = { ms -> handler.post { HapticManager.spike(this); SoundAlertManager.play(this, SoundAlertManager.AlertType.SPIKE); Toast.makeText(this, "⚠️ Spike: ${ms}ms!", Toast.LENGTH_SHORT).show() } }

        maintenance.scheduleDailyMaintenance(); maintenance.scheduleNetworkCheck()

        setContentView(buildUi())
        checkPerms()
        ShortcutHelper.register(this, prefs.getStringSet("selected_games", emptySet()) ?: emptySet())
        if (intent?.action == "ACTION_QUICK_LAUNCH") handler.postDelayed({ startGameMode() }, 500)

        // Check for updates in background
        CoroutineScope(Dispatchers.IO).launch {
            val update = UpdateChecker.check()
            if (update.available) withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "🆕 עדכון זמין: v${update.latestVersion}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val h = hrow(); h.setPadding(20, 36, 20, 12)
        h.addView(tv("Ping\nBooster", 22f, TEXT, bold = true, w = 1f))
        val ctrl = hrow(g = Gravity.CENTER_VERTICAL)
        ctrl.addView(Button(this).apply { text = if (darkMode) "☀️" else "🌙"; textSize = 16f; setBackgroundColor(0); setPadding(8, 0, 8, 0); setOnClickListener { darkMode = !darkMode; recreate() } })
        val pill = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(CARD); setPadding(12, 8, 12, 8) }
        statusDot = View(this).apply { layoutParams = LinearLayout.LayoutParams(10, 10).also { it.marginEnd = 8 }; setBackgroundColor(MUTED) }
        statusLabel = tv("כבוי", 10f, MUTED, bold = true)
        pill.addView(statusDot); pill.addView(statusLabel); ctrl.addView(pill); h.addView(ctrl)
        root.addView(h); root.addView(buildTabs())
        tabContent = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        showTab("home"); root.addView(tabContent); return root
    }

    private fun buildTabs(): LinearLayout {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(CARD2) }
        listOf("home" to "⚡", "game" to "🎮", "hw" to "🖥", "net" to "📡", "analytics" to "📈", "settings" to "⚙️").forEach { (id, lbl) ->
            bar.addView(Button(this).apply { text = lbl; textSize = 14f; setTextColor(MUTED); setBackgroundColor(0); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { showTab(id) } })
        }; return bar
    }

    private fun showTab(id: String) {
        tabContent.removeAllViews()
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        scroll.addView(when (id) { "home" -> homeTab(); "game" -> gameTab(); "hw" -> hwTab(); "net" -> netTab(); "analytics" -> analyticsTab(); else -> settingsTab() })
        tabContent.addView(scroll)
    }

    // ── HOME TAB ──────────────────────────────────────────────────────────────
    private fun homeTab(): View {
        val root = col(pad = 20)
        val pc = card(mb = 14, pv = 28)
        powerBtn = AnimatedPowerButton(this).apply { layoutParams = LinearLayout.LayoutParams(180, 180).also { it.gravity = Gravity.CENTER; it.bottomMargin = 16 }; setOnClickListener { if (isActive) stop() else startGameMode() } }
        tvPrediction = tv("ML Predictor: אין נתונים", 10f, MUTED2, g = Gravity.CENTER, mt = 4)
        pc.addView(powerBtn); pc.addView(tv("לחץ להפעלה", 14f, MUTED, bold = true, g = Gravity.CENTER)); pc.addView(tvPrediction); root.addView(pc)

        // Profiles row
        val prc = card(mb = 12); prc.gravity = Gravity.START; prc.addView(tv("🎮 פרופיל", 12f, TEXT, bold = true, mb = 8))
        val pr = hrow()
        profileMgr.defaults.forEach { p -> pr.addView(Button(this).apply { text = p.name; textSize = 9f; setTextColor(ACCENT); setBackgroundColor(if (darkMode) 0xFF0A1F3A.toInt() else 0xFFDCEEFF.toInt()); setPadding(8, 5, 8, 5); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = 6 }; setOnClickListener { profileMgr.applyToPrefs(p, prefs); HapticManager.success(this@MainActivity); Toast.makeText(this@MainActivity, "✅ ${p.name}", Toast.LENGTH_SHORT).show() } }) }
        prc.addView(pr); root.addView(prc)

        // Stats grid
        fun sg() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 8 } }
        fun sc(label: String, color: Int, c: LinearLayout): TextView { val cc = col(pv = 10); cc.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = 8 }; cc.setBackgroundColor(CARD); val v = tv("—", 15f, color, mono = true, g = Gravity.CENTER); cc.addView(v); cc.addView(tv(label, 9f, MUTED, g = Gravity.CENTER, mt = 3)); c.addView(cc); return v }
        val r1 = sg(); statPing = sc("Ping", ORANGE, r1); statLoss = sc("Loss%", RED, r1); statScore = sc("Score", GREEN, r1); root.addView(r1)
        val r2 = sg(); statCpu = sc("CPU%", 0xFFA259FF.toInt(), r2); statRam = sc("RAM", ACCENT, r2); statTemp = sc("Temp", GREEN, r2); root.addView(r2)
        val r3 = sg(); statFps = sc("FPS", GREEN, r3); tvBattery = sc("Battery", GREEN, r3); root.addView(r3)

        pingGraph = PingGraphView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150).also { it.bottomMargin = 12 }; setBackgroundColor(CARD) }
        root.addView(pingGraph)

        val ac = card(mb = 12); val ah = hrow(g = Gravity.CENTER_VERTICAL)
        ah.addView(tv("🎮 משחקים", 13f, TEXT, bold = true, w = 1f))
        ah.addView(Button(this).apply { text = "ערוך"; textSize = 10f; setTextColor(ACCENT); setBackgroundColor(if (darkMode) 0xFF0A1F3A.toInt() else 0xFFDCEEFF.toInt()); setPadding(12, 4, 12, 4); setOnClickListener { startActivityForResult(Intent(this@MainActivity, AppSelectionActivity::class.java), APP_SEL) } })
        ah.addView(Button(this).apply { text = "🔍"; textSize = 10f; setTextColor(ORANGE); setBackgroundColor(0); setOnClickListener { autoDetect() } })
        tvGames = tv("", 11f, MUTED2, mt = 8); ac.addView(ah); ac.addView(tvGames); root.addView(ac)
        return root
    }

    // ── GAME TAB ──────────────────────────────────────────────────────────────
    private fun gameTab(): View {
        val root = col(pad = 20)
        root.addView(tv("🎮 Per-Game Settings", 14f, TEXT, bold = true, mb = 14))

        val games = prefs.getStringSet("selected_games", emptySet()) ?: emptySet()
        if (games.isEmpty()) { root.addView(tv("בחר משחקים קודם", 13f, MUTED, g = Gravity.CENTER)); return root }

        games.forEach { pkg ->
            val cfg = perGameMgr.getConfig(pkg)
            val gc = card(mb = 14); gc.gravity = Gravity.START
            gc.addView(tv("🎮 ${cfg.gameName}", 13f, ACCENT, bold = true, mb = 10))
            listOf("MTU" to "${cfg.mtu}B", "Target FPS" to "${cfg.targetFps}", "Spike Threshold" to "${cfg.spikeThreshold}ms", "DSCP EF" to if (cfg.dscpEf) "✅" else "❌", "RST Burst" to if (cfg.rstBurst) "✅" else "❌", "EQ Preset" to cfg.eqPreset, "Region" to cfg.preferredRegion).forEach { (l, v) ->
                val r = hrow(g = Gravity.CENTER_VERTICAL, mb = 6)
                r.addView(tv(l, 11f, MUTED2, w = 0.5f)); r.addView(tv(v, 12f, TEXT, bold = true, w = 0.5f)); gc.addView(r)
            }
            gc.addView(Button(this).apply { text = "החל הגדרות עבור ${cfg.gameName}"; textSize = 11f; setTextColor(0xFF070C18.toInt()); setBackgroundColor(ACCENT); setPadding(0, 10, 0, 10); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = 8 }; setOnClickListener { perGameMgr.applyToPrefs(pkg, prefs); HapticManager.success(this@MainActivity); Toast.makeText(this@MainActivity, "✅ הגדרות ${cfg.gameName} הוחלו", Toast.LENGTH_SHORT).show() } })
            root.addView(gc)
        }

        // Auto server selection
        root.addView(tv("🌐 Auto Server Select", 13f, TEXT, bold = true, mt = 8, mb = 8))
        val tvServer = tv("לא נבדק", 12f, MUTED, mb = 8)
        root.addView(Button(this).apply { text = "🔍 בחר שרת מהיר אוטומטי"; textSize = 12f; setTextColor(0xFF070C18.toInt()); setBackgroundColor(ACCENT); setPadding(0, 12, 0, 12); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 8 }; setOnClickListener { tvServer.text = "סורק..."; AutoServerSelector.selectAndApply("CoD") { result -> tvServer.text = if (result.best != null) "✅ מומלץ: ${result.best.region} (${result.best.pingMs}ms)\nחסם: ${result.blocked.size} שרתים איטיים" else "❌ לא נמצא שרת"; tvServer.setTextColor(if (result.best != null) GREEN else RED) } } })
        root.addView(tvServer)

        // Frame pacing
        root.addView(tv("📊 Frame Pacing", 13f, TEXT, bold = true, mt = 8, mb = 8))
        val fps = framePacing.getStats()
        root.addView(tv("FPS: ${fps.avgFps.toInt()} | Jank: ${String.format("%.1f", fps.jankPercent)}% | ${fps.stability}", 12f, if (fps.jankPercent < 5f) GREEN else ORANGE))

        // ICMP ping
        root.addView(tv("📡 ICMP Ping אמיתי", 13f, TEXT, bold = true, mt = 8, mb = 8))
        val tvIcmp = tv("לא נבדק", 12f, MUTED)
        root.addView(Button(this).apply { text = "📡 Ping ICMP"; textSize = 11f; setTextColor(ACCENT); setBackgroundColor(if (darkMode) 0xFF0A1F3A.toInt() else 0xFFDCEEFF.toInt()); setPadding(0, 10, 0, 10); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 8 }; setOnClickListener { tvIcmp.text = "בודק ICMP..."; CoroutineScope(Dispatchers.Main).launch { val r = IcmpPinger.ping("1.1.1.1"); tvIcmp.text = if (r.reachable) "✅ ICMP: ${r.pingMs}ms → 1.1.1.1" else "❌ ICMP לא זמין"; tvIcmp.setTextColor(if (r.reachable) GREEN else RED) } } })
        root.addView(tvIcmp)
        return root
    }

    // ── HW TAB ────────────────────────────────────────────────────────────────
    private fun hwTab(): View {
        val root = col(pad = 20)
        root.addView(tv("🖥 חומרה", 14f, TEXT, bold = true, mb = 14))
        val hw = hwMonitor.getStats()
        val hwc = card(mb = 14); hwc.gravity = Gravity.START
        hwc.addView(tv("🌡 טמפרטורות", 13f, TEXT, bold = true, mb = 10))
        listOf("CPU ממוצע" to "${hw.avgCpuTemp.toInt()}°C", "GPU" to "${hw.gpuTemp.toInt()}°C", "Surface" to "${hw.skinTemp.toInt()}°C", "Throttling" to if (hw.throttling) "⚠️ כן!" else "✅ לא").forEach { (l, v) ->
            val r = hrow(g = Gravity.CENTER_VERTICAL, mb = 8); r.addView(tv(l, 12f, MUTED2, w = 0.5f)); r.addView(tv(v, 13f, if (v.contains("⚠️")) RED else if (v.contains("✅")) GREEN else ACCENT, bold = true, w = 0.5f)); hwc.addView(r)
        }
        root.addView(hwc)

        val bt = btAudio.getInfo()
        val btc = card(mb = 14); btc.gravity = Gravity.START; btc.addView(tv("🎧 Bluetooth", 13f, TEXT, bold = true, mb = 10))
        if (bt.connected) { listOf("מכשיר" to bt.deviceName, "Codec" to bt.codec, "Latency" to "${bt.latencyMs}ms").forEach { (l, v) -> val r = hrow(g = Gravity.CENTER_VERTICAL, mb = 8); r.addView(tv(l, 12f, MUTED2, w = 0.4f)); r.addView(tv(v, 12f, ACCENT, w = 0.6f)); btc.addView(r) }; btc.addView(tv(bt.recommendation, 11f, if (bt.latencyMs < 80) GREEN else ORANGE, mt = 6)) } else btc.addView(tv("אין BT מחובר", 12f, MUTED))
        root.addView(btc)

        val cpu = resourceMgr.getCpuBoostManager()
        val perc = card(mb = 14); perc.gravity = Gravity.START; perc.addView(tv("⚡ ביצועים", 13f, TEXT, bold = true, mb = 10))
        listOf(Triple("PerformanceHintManager", if (cpu.isHintManagerAvailable()) "✅" else "❌", if (cpu.isHintManagerAvailable()) GREEN else MUTED), Triple("Sustained", if (cpu.isSustainedPerfSupported()) "✅" else "❌", if (cpu.isSustainedPerfSupported()) GREEN else MUTED), Triple("Cores", "${PacketEngine.getNumCores()}", ACCENT), Triple("MPTCP", if (multiPath.isMptcpAvailable()) "✅" else "❌", if (multiPath.isMptcpAvailable()) GREEN else MUTED)).forEach { (t, s, c) -> val r = hrow(g = Gravity.CENTER_VERTICAL, mb = 8); r.addView(tv(t, 12f, TEXT, w = 0.6f)); r.addView(tv(s, 12f, c, bold = true, w = 0.4f)); perc.addView(r) }
        root.addView(perc)

        root.addView(Button(this).apply { text = "🚀 נתב כל המשאבים"; textSize = 13f; setTextColor(0xFF070C18.toInt()); setBackgroundColor(ACCENT); setPadding(0, 14, 0, 14); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); setOnClickListener { val pkgs = prefs.getStringSet("selected_games", null) ?: setOf("com.activision.callofduty.shooter"); resourceMgr.activate(pkgs); gameApi.reportGameplayStarted(pkgs.first()); displayMgr.setMaxRefreshRate(this@MainActivity); HapticManager.success(this@MainActivity); Toast.makeText(this@MainActivity, "✅ הכל מנותב!", Toast.LENGTH_SHORT).show() } })
        return root
    }

    // ── NET TAB ───────────────────────────────────────────────────────────────
    private fun netTab(): View {
        val root = col(pad = 20); root.addView(tv("📡 רשת", 14f, TEXT, bold = true, mb = 14))
        val wifi = networkMon.getWifiInfo()
        listOf("📶 WiFi" to WiFiBandHelper.getCurrent5GhzStatus(this), "📊 RSSI" to WiFiBandHelper.getRssiQuality(wifi?.rssi ?: -100), "🌐 DNS" to networkMon.getActiveDnsServers().joinToString(", ").ifEmpty { "N/A" }, "📦 MTU" to "${networkMon.getActiveMtu()}B", "🌍 Roaming" to if (roamingGuard.isRoaming()) "⚠️ ${roamingGuard.getRoamingCountry()}" else "✅ לא").forEach { (l, v) -> val r = hrow(g = Gravity.CENTER_VERTICAL, mb = 10); r.addView(tv(l, 12f, MUTED2, w = 0.4f)); r.addView(tv(v, 12f, ACCENT, bold = true, w = 0.6f)); root.addView(r) }
        val cellInfo = cell.getCellInfo()
        root.addRow(tv("📱 ${cellInfo.technology} · ${cellInfo.operator} · ${cellInfo.signalDbm}dBm ${cellInfo.rating}", 12f, ACCENT, mt = 8, mb = 12))
        val tvDoh = tv("", 12f, MUTED); val tvLeak = tv("", 12f, MUTED)
        root.addView(Button(this).apply { text = "🔒 DoH Test"; textSize = 11f; setTextColor(ACCENT); setBackgroundColor(if (darkMode) 0xFF0A1F3A.toInt() else 0xFFDCEEFF.toInt()); setPadding(0, 10, 0, 10); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 8 }; setOnClickListener { tvDoh.text = "בודק..."; CoroutineScope(Dispatchers.Main).launch { val r = DoHResolver.resolve("cloudflare.com"); tvDoh.text = if (r.ip != null) "✅ DoH: ${r.ip} (${r.latencyMs}ms)" else "❌ DoH נכשל"; tvDoh.setTextColor(if (r.ip != null) GREEN else RED) } } })
        root.addView(tvDoh)
        root.addView(Button(this).apply { text = "🔍 Leak Test"; textSize = 11f; setTextColor(ACCENT); setBackgroundColor(if (darkMode) 0xFF0A1F3A.toInt() else 0xFFDCEEFF.toInt()); setPadding(0, 10, 0, 10); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 8 }; setOnClickListener { tvLeak.text = "בודק..."; CoroutineScope(Dispatchers.Main).launch { val r = LeakTester.run(GameModeVpnService.isRunning); tvLeak.text = r.summary; tvLeak.setTextColor(if (r.dnsLeak) RED else GREEN) } } })
        root.addView(tvLeak)
        return root
    }

    // ── ANALYTICS TAB ─────────────────────────────────────────────────────────
    private fun analyticsTab(): View {
        val root = col(pad = 20); root.addView(tv("📈 אנליטיקה", 14f, TEXT, bold = true, mb = 14))
        analytics.getBestHours().take(4).forEach { h -> val r = hrow(g = Gravity.CENTER_VERTICAL, mb = 8); r.addView(tv(String.format("%02d:00", h.hour), 12f, ACCENT, mono = true, w = 0.25f)); r.addView(tv("${h.avgPing.toInt()}ms", 13f, ORANGE, bold = true, mono = true, w = 0.25f)); r.addView(tv(h.rating, 11f, GREEN, w = 0.5f)); root.addView(r) }
        val heatmap = HeatmapView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200).also { it.bottomMargin = 12 }; setBackgroundColor(CARD); setData(db.getLast(200)) }
        root.addView(heatmap)
        root.addView(Button(this).apply { text = "📁 CSV"; textSize = 11f; setTextColor(0xFF070C18.toInt()); setBackgroundColor(ACCENT); setPadding(0, 10, 0, 10); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 8 }; setOnClickListener { BackupManager.shareBackup(this@MainActivity) } })
        root.addView(Button(this).apply { text = "💾 גיבוי הגדרות"; textSize = 11f; setTextColor(ACCENT); setBackgroundColor(if (darkMode) 0xFF0A1F3A.toInt() else 0xFFDCEEFF.toInt()); setPadding(0, 10, 0, 10); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = 8 }; setOnClickListener { BackupManager.shareBackup(this@MainActivity) } })
        db.getLast(5).forEach { s -> val sc = card(mb = 8); sc.gravity = Gravity.START; val d = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s.startTime)); sc.addView(tv("$d · ${s.durationSec / 60}min", 10f, MUTED2, mb = 4)); val sr = hrow(); listOf("${s.avgPing}ms" to ORANGE, "⚡${s.minPing}" to GREEN, "💀${String.format("%.1f", s.packetLoss)}%" to RED).forEach { (t, c) -> sr.addView(tv(t, 10f, c, mono = true, w = 1f)) }; sc.addView(sr); sc.addView(Button(this).apply { text = "📤"; textSize = 10f; setTextColor(ACCENT); setBackgroundColor(0); setPadding(0, 4, 0, 4); setOnClickListener { SessionShareManager.shareSession(this@MainActivity, s) } }); root.addView(sc) }
        return root
    }

    // ── SETTINGS TAB ──────────────────────────────────────────────────────────
    private fun settingsTab(): View {
        val root = col(pad = 20); root.addView(tv("⚙️ הגדרות", 14f, TEXT, bold = true, mb = 14))
        fun sw(icon: String, lbl: String, sub: String, key: String, def: Boolean = false, action: ((Boolean) -> Unit)? = null) {
            val r = hrow(g = Gravity.CENTER_VERTICAL, mb = 12); val t = col(); t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            t.addView(tv("$icon  $lbl", 12f, TEXT)); t.addView(tv(sub, 9f, MUTED, mt = 2))
            val s = Switch(this).apply { isChecked = prefs.getBoolean(key, def); thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT); setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(key, c).apply(); action?.invoke(c) } }
            r.addView(t); r.addView(s); root.addView(r)
        }
        sw("⚡","Auto-Trigger","UsageEvents streaming","auto_trigger"){on->if(on)startService(Intent(this,AutoTriggerService::class.java)) else startService(Intent(this,AutoTriggerService::class.java).apply{action="STOP"})}
        sw("🌐","MPTCP","WiFi + 5G במקביל","mptcp",true){on->if(on)multiPath.activateAll() else multiPath.deactivateAll()}
        sw("🖥","CPU Boost","WakeLock + Hint + FIFO","cpu_boost",true)
        sw("🔴","RST Burst","קוטע TCP ברקע","rst_burst",true)
        sw("🔵","DSCP QoS","EF priority","qos_dscp",true)
        sw("📺","120Hz","קצב רענון מקסימלי","hz_lock",true){on->if(on)displayMgr.setMaxRefreshRate(this) else displayMgr.clearMaxRefreshRate(this)}
        sw("🔕","DND","שקט מוחלט","dnd",true){on->if(on&&!shield.isDndGranted())startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))}
        sw("🎧","Audio FX","EQ + Loudness","audio_eq",true)
        sw("🔔","Sound Alerts","צלילי Spike/הפעלה","sound_alerts",true)
        sw("🌡","Thermal Monitor","overlay צף","thermal",true)
        sw("📱","Gyro DND","גריפה הפוכה = שקט","gyro_dnd",true)
        sw("💀","Kill BG","הורג תהליכי רקע","kill_bg")

        root.addView(tv("⚠️ סף Spike", 13f, TEXT, mb = 6))
        val sv = tv("${prefs.getInt("spike_threshold", 80)}ms", 13f, ORANGE, bold = true)
        val sk = SeekBar(this).apply { max = 200; progress = prefs.getInt("spike_threshold", 80); setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { sv.text = "${p}ms"; prefs.edit().putInt("spike_threshold", p).apply() }; override fun onStartTrackingTouch(s: SeekBar?) {}; override fun onStopTrackingTouch(s: SeekBar?) {} }) }
        val sr = hrow(g = Gravity.CENTER_VERTICAL); sr.addView(sk.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }); sr.addView(sv); root.addView(sr)
        return root
    }

    // ── Actions ───────────────────────────────────────────────────────────────
    private fun autoDetect() {
        Toast.makeText(this, "🔍 מזהה...", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch { val games = GameAutoDetector.detect(this@MainActivity).take(5); withContext(Dispatchers.Main) { val pkgs = games.map { it.packageName }.toSet(); prefs.edit().putStringSet("selected_games", pkgs).apply(); updateGames(); ShortcutHelper.register(this@MainActivity, pkgs); Toast.makeText(this@MainActivity, "✅ ${games.size} משחקים", Toast.LENGTH_LONG).show() } }
    }

    private fun startGameMode() { val vi = VpnService.prepare(this); if (vi != null) startActivityForResult(vi, vpnReqCode) else launch() }
    override fun onActivityResult(rq: Int, rc: Int, d: Intent?) { super.onActivityResult(rq, rc, d); when (rq) { vpnReqCode -> if (rc == RESULT_OK) launch(); APP_SEL -> if (rc == RESULT_OK) { updateGames(); ShortcutHelper.register(this, prefs.getStringSet("selected_games", emptySet()) ?: emptySet()) } } }

    private fun launch() {
        val pkgs = ArrayList(prefs.getStringSet("selected_games", null) ?: setOf("com.activision.callofduty.shooter"))
        val firstPkg = pkgs.first()

        // Apply per-game config
        perGameMgr.applyToPrefs(firstPkg, prefs)

        // DNS Prefetch
        val gameName = if (firstPkg.contains("activision")) "CoD Mobile" else "PUBG Mobile"
        DnsPrefetcher.prefetch(gameName)

        // RST burst
        if (prefs.getBoolean("rst_burst", true)) { startService(Intent(this, GameModeVpnService::class.java).apply { action = GameModeVpnService.ACTION_RESET_CONNECTIONS }); Thread.sleep(120) }

        // VPN
        startService(Intent(this, GameModeVpnService::class.java).apply { putStringArrayListExtra(GameModeVpnService.EXTRA_PACKAGES, pkgs) })

        // All boosts
        if (prefs.getBoolean("cpu_boost", true)) resourceMgr.activate(pkgs.toSet())
        if (prefs.getBoolean("mptcp", true)) multiPath.activateAll()
        if (prefs.getBoolean("dnd", true) || prefs.getBoolean("audio_focus", true)) shield.activateAll()
        if (prefs.getBoolean("thermal", true)) thermal.start()
        if (prefs.getBoolean("audio_eq", true)) audioFx.activate()
        if (prefs.getBoolean("hz_lock", true)) displayMgr.setMaxRefreshRate(this)
        gameApi.reportGameplayStarted(firstPkg)

        // madvise for game process
        CoroutineScope(Dispatchers.IO).launch {
            Thread.sleep(2000)
            val pid = resourceMgr.getProcessBoostManager().findGamePid(firstPkg)
            if (pid != null) PacketEngine.adviseKeepInRam(pid)
        }

        PacketEngine.resetCounters(); pingPredictor.reset(); framePacing.reset()
        sessionStats = SessionStats(this, firstPkg); sessionStats.start()
        sessionStats.onSpikeDetected = { ms -> handler.post { HapticManager.spike(this); SoundAlertManager.play(this, SoundAlertManager.AlertType.SPIKE); Toast.makeText(this, "⚠️ Spike: ${ms}ms!", Toast.LENGTH_SHORT).show() } }

        SoundAlertManager.play(this, SoundAlertManager.AlertType.ACTIVATE)
        setActive(true); HapticManager.activate(this)
    }

    private fun stop() {
        startService(Intent(this, GameModeVpnService::class.java).apply { action = GameModeVpnService.ACTION_STOP })
        resourceMgr.deactivate(); shield.deactivateAll(); thermal.stop(); audioFx.deactivate()
        displayMgr.clearMaxRefreshRate(this); multiPath.deactivateAll()
        gameApi.reportLoadingStarted(); overlay.hide(); liveNotif.cancel()
        val rec = sessionStats.finish()
        rec?.let { Toast.makeText(this, "📊 avg ${it.avgPing}ms · loss ${String.format("%.1f", it.packetLoss)}%", Toast.LENGTH_LONG).show() }
        SoundAlertManager.play(this, SoundAlertManager.AlertType.DEACTIVATE)
        setActive(false); GameBoostWidget.updateAllWidgets(this); HapticManager.deactivate(this)
    }

    private fun setActive(a: Boolean) {
        isActive = a; powerBtn.setActive(a)
        statusDot.setBackgroundColor(if (a) GREEN else MUTED)
        statusLabel.text = if (a) "פעיל" else "כבוי"; statusLabel.setTextColor(if (a) GREEN else MUTED)
        if (a) { handler.post(statsUpdater); startPingMeasurement() } else { stopPingMeasurement(); handler.removeCallbacks(statsUpdater); if (::statPing.isInitialized) { statPing.text = "—"; statLoss.text = "—"; statCpu.text = "—"; statRam.text = "—"; statScore.text = "—"; statFps.text = "—" } }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun LinearLayout.addRow(v: View) { v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); addView(v) }
    private fun checkPerms() { if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    override fun onResume() { super.onResume(); updateGames() }
    private fun updateGames() { val sel = prefs.getStringSet("selected_games", emptySet()) ?: emptySet(); if (::tvGames.isInitialized) tvGames.text = if (sel.isEmpty()) "לא נבחרו" else sel.mapNotNull { try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() } catch (e: Exception) { it } }.joinToString(" · ") }
    private fun col(pv: Int = 0, pad: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; if (pad > 0) setPadding(pad, pad, pad, pad * 2) else if (pv > 0) setPadding(14, pv, 14, pv); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
    private fun hrow(g: Int = Gravity.START, mb: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = g; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = mb } }
    private fun card(mb: Int = 0, pv: Int = 14) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(CARD); setPadding(16, pv, 16, pv); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = mb } }
    private fun tv(t: String, sz: Float, col: Int, bold: Boolean = false, w: Float = -1f, g: Int = Gravity.END, mono: Boolean = false, mt: Int = 0, mb: Int = 0) = TextView(this).apply { text = t; textSize = sz; setTextColor(col); this.gravity = g; if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD; if (mono) typeface = android.graphics.Typeface.MONOSPACE; layoutParams = (if (w > 0) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)).also { it.topMargin = mt; it.bottomMargin = mb } }
    override fun onDestroy() { mainScope.cancel(); stopPingMeasurement(); handler.removeCallbacks(statsUpdater); networkMon.stop(); battery.stop(); gyro.stop(); gameApi.stopThermalMonitoring(); roamingGuard.stop(); voiceChat.stop(); framePacing.stop(); if (isActive) { resourceMgr.deactivate(); shield.deactivateAll(); thermal.stop(); audioFx.deactivate(); multiPath.deactivateAll() }; super.onDestroy() }
}
