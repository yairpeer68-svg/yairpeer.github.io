package com.yourname.gamemodevpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class GameModeVpnService : VpnService() {

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var packetThread: Thread? = null
    private val reconnectAttempts = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastPackages: List<String> = listOf("com.activision.callofduty.shooter")

    companion object {
        const val TAG = "GameModeVPN"
        const val ACTION_STOP = "STOP_VPN"
        const val ACTION_RESET_CONNECTIONS = "RESET_CONNECTIONS"
        const val EXTRA_PACKAGES = "GAME_PACKAGES"
        const val NOTIF_ID = 7001
        const val CHANNEL_ID = "gamevpn_service"
        private val RECONNECT_DELAYS_MS = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000)
        @Volatile var isRunning = false
        var instance: GameModeVpnService? = null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopVpn(); return START_NOT_STICKY }
            ACTION_RESET_CONNECTIONS -> { injectRstBurst(); return START_STICKY }
        }
        val packages = intent?.getStringArrayListExtra(EXTRA_PACKAGES)
            ?: arrayListOf("com.activision.callofduty.shooter")
        lastPackages = packages
        startForeground(NOTIF_ID, buildNotification("Ping Booster פעיל"))
        startVpn(packages)
        return START_STICKY
    }

    private fun startVpn(allowedPackages: List<String>) {
        if (vpnInterface != null) return
        try {
            val builder = Builder()
                .setSession("GameMode_PingBooster")
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                // IPv6 support
                .addAddress("fd00::2", 120)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1")
                .addDnsServer("2606:4700:4700::1111")
                .setMtu(1500)
                .setBlocking(true)
            allowedPackages.forEach { pkg ->
                try { builder.addDisallowedApplication(pkg) } catch (_: Exception) { }
            }
            val iface = builder.establish() ?: run {
                Log.e(TAG, "VPN establish() returned null")
                scheduleReconnect()
                return
            }
            vpnInterface = iface
            isRunning = true
            running.set(true)
            instance = this
            reconnectAttempts.set(0)

            packetThread = Thread {
                val cores = PacketEngine.pinToBigCores()
                val rt = PacketEngine.setRealtimeScheduling()
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                Log.i(TAG, "${PacketEngine.getVersion()} | cores=$cores rt=$rt")
                processPackets()
                // If running flag is still true, the tunnel dropped unexpectedly → kill switch
                if (running.get()) {
                    Log.w(TAG, "VPN tunnel dropped — kill switch triggered")
                    mainHandler.post { handleKillSwitch() }
                }
            }.apply { name = "VPN-PacketThread"; isDaemon = true; start() }
        } catch (e: Exception) {
            Log.e(TAG, "VPN start error: ${e.message}")
            scheduleReconnect()
        }
    }

    private fun handleKillSwitch() {
        // Close the old interface and block all traffic while reconnecting
        try { vpnInterface?.close() } catch (_: Exception) { }
        vpnInterface = null
        isRunning = false
        // Build a blocking VPN with no routes to drop all traffic until reconnected
        try {
            val blockBuilder = Builder()
                .setSession("KillSwitch")
                .addAddress("10.0.0.3", 32)
                .setMtu(1500)
                .setBlocking(false)
            blockBuilder.establish()?.close() // establish then immediately close = block all
        } catch (_: Exception) { }
        updateNotification("🔒 Kill Switch פעיל — מתחבר מחדש...")
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val attempt = reconnectAttempts.getAndIncrement()
        if (attempt >= RECONNECT_DELAYS_MS.size) {
            Log.e(TAG, "Max reconnect attempts reached — stopping")
            stopVpn()
            return
        }
        val delay = RECONNECT_DELAYS_MS[attempt]
        Log.i(TAG, "Reconnect attempt ${attempt + 1} in ${delay}ms")
        mainHandler.postDelayed({
            if (running.get() || isRunning) return@postDelayed // already up
            startVpn(lastPackages)
        }, delay)
    }

    private fun processPackets() {
        val vpn = vpnInterface ?: return
        val input = FileInputStream(vpn.fileDescriptor)
        val output = FileOutputStream(vpn.fileDescriptor)
        val buf = ByteArray(32767)
        while (running.get()) {
            try {
                val len = input.read(buf)
                if (len <= 0) continue
                val pkt = buf.copyOf(len)
                val result = PacketEngine.processPacket(pkt, len, false)
                if (result >= 0) {
                    val writeLen = if (result > 0 && result <= len) result else len
                    output.write(pkt, 0, writeLen)
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "Packet error: ${e.message}")
                break
            }
        }
        try { input.close() } catch (_: Exception) { }
        try { output.close() } catch (_: Exception) { }
    }

    fun injectRstBurst() {
        val currentVpn = vpnInterface ?: return
        Thread {
            try {
                val output = FileOutputStream(currentVpn.fileDescriptor)
                val synTemplate = byteArrayOf(
                    0x45, 0x00, 0x00, 0x28.toByte(), 0x00, 0x01, 0x40, 0x00, 0x40, 0x06, 0x00, 0x00,
                    0x0A, 0x00, 0x00, 0x02, 0x08, 0x08, 0x08, 0x08.toByte(),
                    0x04, 0x00, 0x00, 0x50, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
                    0x50, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                )
                val rst = PacketEngine.buildRst(synTemplate, synTemplate.size)
                if (rst != null) repeat(3) {
                    try { output.write(rst) } catch (_: Exception) { }
                    Thread.sleep(50)
                }
                Log.i(TAG, "RST burst sent")
            } catch (e: Exception) {
                Log.w(TAG, "RST burst error: ${e.message}")
            }
        }.apply { name = "RST-Burst"; isDaemon = true; start() }
    }

    private fun stopVpn() {
        running.set(false)
        isRunning = false
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        packetThread?.interrupt()
        packetThread = null
        try { vpnInterface?.close() } catch (_: Exception) { }
        vpnInterface = null
        stopForeground(true)
        stopSelf()
        Log.i(TAG, "VPN stopped")
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Ping Booster", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Game VPN service"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Ping Booster")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Ping Booster")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    override fun onDestroy() { stopVpn(); super.onDestroy() }
}
