package com.yourname.gamemodevpn.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

/**
 * Sends ping updates from the phone app to Wear OS watches.
 * Call pushPingUpdate() every time the ping changes.
 */
object WearDataSender {

    private const val TAG = "WearDataSender"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun pushPingUpdate(ctx: Context, pingMs: Int, vpnActive: Boolean) {
        scope.launch {
            try {
                val map = PutDataMapRequest.create(WearMainActivity.PATH_PING_UPDATE).apply {
                    dataMap.putInt(WearMainActivity.KEY_PING_MS, pingMs)
                    dataMap.putBoolean(WearMainActivity.KEY_VPN_ACTIVE, vpnActive)
                    dataMap.putLong("ts", System.currentTimeMillis()) // force update even if same ping
                }
                Wearable.getDataClient(ctx).putDataItem(map.asPutDataRequest().setUrgent()).await()
                Log.d(TAG, "Sent ping=$pingMs active=$vpnActive to watch")
            } catch (e: Exception) {
                Log.w(TAG, "Wear send failed: ${e.message}")
            }
        }
    }
}
