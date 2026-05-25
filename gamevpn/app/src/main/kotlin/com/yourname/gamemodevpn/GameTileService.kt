package com.yourname.gamemodevpn

import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

class GameTileService : TileService() {

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateTile()
            if (GameModeVpnService.isRunning) handler.postDelayed(this, 1200)
        }
    }

    override fun onTileAdded() { updateTile() }

    override fun onStartListening() {
        updateTile()
        if (GameModeVpnService.isRunning) handler.post(updateRunnable)
    }

    override fun onStopListening() { handler.removeCallbacks(updateRunnable) }

    override fun onClick() {
        handler.removeCallbacks(updateRunnable)
        if (GameModeVpnService.isRunning) {
            startService(Intent(this, GameModeVpnService::class.java).apply { action = GameModeVpnService.ACTION_STOP })
        } else {
            if (VpnService.prepare(this) != null) {
                startActivityAndCollapse(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } else {
                val pkgs = getSharedPreferences("gameboost", MODE_PRIVATE).getStringSet("selected_games", null) ?: setOf("com.activision.callofduty.shooter")
                startService(Intent(this, GameModeVpnService::class.java).apply { putStringArrayListExtra(GameModeVpnService.EXTRA_PACKAGES, ArrayList(pkgs)) })
                handler.post(updateRunnable)
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = GameModeVpnService.isRunning
        val ping = getSharedPreferences("gameboost", MODE_PRIVATE).getInt("last_ping", 0)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (running && ping > 0) "Game ON · ${ping}ms" else if (running) "Game ON ⚡" else "Game OFF"
        tile.contentDescription = if (running) "פעיל — לחץ לכיבוי" else "כבוי — לחץ להפעלה"
        tile.updateTile()
    }
}
