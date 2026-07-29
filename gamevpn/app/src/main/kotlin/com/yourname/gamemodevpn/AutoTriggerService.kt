package com.yourname.gamemodevpn

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class AutoTriggerService : Service() {

    private var running = false
    private val prefs by lazy { getSharedPreferences("gameboost", Context.MODE_PRIVATE) }
    private lateinit var shield: GameShieldManager
    private lateinit var thermal: ThermalMonitor
    private lateinit var multiPath: MultiPathManager
    private lateinit var usageMonitor: UsageEventsMonitor  // Stream-based, faster
    private lateinit var roamingGuard: RoamingGuard
    private lateinit var adaptive: AdaptiveLearner
    private lateinit var liveNotif: LiveNotificationManager

    companion object {
        const val CHANNEL_ID = "auto_trigger_ch"
        const val NOTIF_ID   = 1001
    }

    override fun onCreate() {
        super.onCreate()
        shield = GameShieldManager(this); thermal = ThermalMonitor(this)
        multiPath = MultiPathManager(this); roamingGuard = RoamingGuard(this)
        adaptive = AdaptiveLearner(this); liveNotif = LiveNotificationManager(this)
        liveNotif.init()

        // UsageEvents stream — faster than polling
        usageMonitor = UsageEventsMonitor(this).apply {
            onGameForeground = { pkg -> onGameStart(pkg) }
            onGameBackground = { pkg -> onGameStop(pkg) }
        }

        createChannel()
        startForeground(NOTIF_ID, buildNotif("⏳ ממתין למשחק..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        if (!running) {
            running = true
            usageMonitor.start()
            roamingGuard.start()
            multiPath.activateAll()
            roamingGuard.onRoamingDetected = { msg -> updateNotif("🌍 $msg") }
        }
        return START_STICKY
    }

    private fun onGameStart(pkg: String) {
        val games = prefs.getStringSet("selected_games", emptySet()) ?: emptySet()
        if (!games.contains(pkg)) return
        Log.i("AutoTrigger", "🎮 Game started: $pkg (via UsageEvents)")
        updateNotif("🎮 משחק פועל: $pkg")

        // DNS Prefetch for the game
        val gameName = when { pkg.contains("activision") -> "CoD Mobile"; pkg.contains("pubg") -> "PUBG Mobile"; else -> "Free Fire" }
        DnsPrefetcher.prefetch(gameName) { Log.d("AutoTrigger", "Prefetched: ${it.host}") }

        // VPN
        if (!GameModeVpnService.isRunning && VpnService.prepare(this) == null) {
            startService(Intent(this, GameModeVpnService::class.java).apply {
                putStringArrayListExtra(GameModeVpnService.EXTRA_PACKAGES, ArrayList(games))
            })
        }
        shield.activateAll()
        if (prefs.getBoolean("thermal", true)) thermal.start()
        GameAccessibilityService.setGameActive(true)
        liveNotif.update(0, 0f, 0, 0f, gameName)
    }

    private fun onGameStop(pkg: String) {
        Log.i("AutoTrigger", "🎮 Game stopped: $pkg")
        updateNotif("⏳ ממתין למשחק...")
        startService(Intent(this, GameModeVpnService::class.java).apply { action = GameModeVpnService.ACTION_STOP })
        shield.deactivateAll(); thermal.stop(); liveNotif.cancel()
        GameAccessibilityService.setGameActive(false)
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Auto Game Trigger", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotif(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Ping Booster").setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_compass).build()

    private fun updateNotif(text: String) =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotif(text))

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running = false; usageMonitor.stop(); roamingGuard.stop()
        multiPath.deactivateAll(); liveNotif.cancel(); super.onDestroy()
    }
}
