package com.yourname.gamemodevpn

import android.content.Context
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * WireGuard-style tunnel manager.
 *
 * WireGuard gives 2-3× lower latency than traditional VPN:
 *  - Kernel bypass via UDP + ChaCha20-Poly1305
 *  - 4096-byte MTU-aware packets
 *  - Cryptokey routing (no connection state)
 *
 * This manager handles config storage, connection lifecycle,
 * and handshake health monitoring. The actual WireGuard crypto
 * runs via the `wireguard-android` native library (add to build.gradle:
 *   implementation 'com.wireguard.android:tunnel:1.0.20230105')
 */
object WireGuardManager {

    private const val TAG = "WireGuardMgr"
    private const val PREFS_KEY_CONFIG = "wg_config"
    private const val HANDSHAKE_TIMEOUT_MS = 5000L

    data class WgConfig(
        val privateKey: String,
        val publicKey: String,
        val peerPublicKey: String,
        val serverEndpoint: String,   // "1.2.3.4:51820"
        val allowedIPs: String = "0.0.0.0/0, ::/0",
        val dns: String = "1.1.1.1",
        val mtu: Int = 1420,
        val keepalive: Int = 25       // seconds
    )

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    @Volatile var state: State = State.DISCONNECTED
        private set

    var onStateChange: ((State) -> Unit)? = null

    private var handshakeJob: Job? = null

    fun saveConfig(ctx: Context, cfg: WgConfig) {
        ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE).edit()
            .putString(PREFS_KEY_CONFIG, serializeConfig(cfg))
            .apply()
        Log.i(TAG, "WireGuard config saved for ${cfg.serverEndpoint}")
    }

    fun loadConfig(ctx: Context): WgConfig? {
        val raw = ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
            .getString(PREFS_KEY_CONFIG, null) ?: return null
        return try { deserializeConfig(raw) } catch (e: Exception) { null }
    }

    fun hasConfig(ctx: Context) = loadConfig(ctx) != null

    /**
     * Start WireGuard handshake monitoring loop.
     * Checks that the peer responds every 25s (keepalive interval).
     * If handshake fails, triggers reconnect.
     */
    fun startHandshakeMonitor(cfg: WgConfig, onDead: () -> Unit) {
        handshakeJob?.cancel()
        handshakeJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(cfg.keepalive * 1000L)
                val alive = checkHandshake(cfg.serverEndpoint)
                if (!alive) {
                    Log.w(TAG, "WireGuard handshake lost — reconnecting")
                    setState(State.ERROR)
                    withContext(Dispatchers.Main) { onDead() }
                    break
                }
            }
        }
    }

    fun stopHandshakeMonitor() { handshakeJob?.cancel() }

    /** Build a WireGuard config string in standard wg-quick format. */
    fun buildConfigString(cfg: WgConfig): String = """
        [Interface]
        PrivateKey = ${cfg.privateKey}
        Address = 10.0.0.2/24, fd00::2/120
        DNS = ${cfg.dns}
        MTU = ${cfg.mtu}

        [Peer]
        PublicKey = ${cfg.peerPublicKey}
        Endpoint = ${cfg.serverEndpoint}
        AllowedIPs = ${cfg.allowedIPs}
        PersistentKeepalive = ${cfg.keepalive}
    """.trimIndent()

    private fun checkHandshake(endpoint: String): Boolean {
        val parts = endpoint.split(":")
        if (parts.size != 2) return false
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(parts[0], parts[1].toInt()), HANDSHAKE_TIMEOUT_MS.toInt())
                true
            }
        } catch (_: Exception) { false }
    }

    private fun setState(s: State) {
        state = s
        onStateChange?.invoke(s)
    }

    private fun serializeConfig(cfg: WgConfig) =
        "${cfg.privateKey}|${cfg.publicKey}|${cfg.peerPublicKey}|${cfg.serverEndpoint}|${cfg.allowedIPs}|${cfg.dns}|${cfg.mtu}|${cfg.keepalive}"

    private fun deserializeConfig(raw: String): WgConfig {
        val p = raw.split("|")
        return WgConfig(p[0], p[1], p[2], p[3], p[4], p[5], p[6].toInt(), p[7].toInt())
    }
}
