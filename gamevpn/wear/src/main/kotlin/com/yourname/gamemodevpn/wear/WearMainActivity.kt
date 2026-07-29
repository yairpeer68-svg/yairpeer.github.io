package com.yourname.gamemodevpn.wear

import android.app.Activity
import android.graphics.Color
import android.os.*
import android.view.Gravity
import android.widget.*
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*

/**
 * Wear OS companion app — shows live ping on the watch face.
 * Receives ping updates from the phone via DataLayer API.
 */
class WearMainActivity : Activity(), DataClient.OnDataChangedListener {

    private lateinit var tvPing: TextView
    private lateinit var tvStatus: TextView
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val PATH_PING_UPDATE = "/ping_update"
        const val KEY_PING_MS = "ping_ms"
        const val KEY_VPN_ACTIVE = "vpn_active"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
        }

        tvStatus = TextView(this).apply {
            text = "⏸ כבוי"; textSize = 12f; setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER
        }

        tvPing = TextView(this).apply {
            text = "--ms"; textSize = 32f; setTextColor(0xFF00FFAA.toInt())
            gravity = Gravity.CENTER; typeface = android.graphics.Typeface.MONOSPACE
        }

        val tvLabel = TextView(this).apply {
            text = "Ping Booster"; textSize = 10f; setTextColor(0xFF3D5570.toInt())
            gravity = Gravity.CENTER
        }

        root.addView(tvLabel)
        root.addView(tvStatus)
        root.addView(tvPing)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
    }

    override fun onPause() {
        super.onPause()
        Wearable.getDataClient(this).removeListener(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == PATH_PING_UPDATE) {
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                val ping = map.getInt(KEY_PING_MS, 0)
                val active = map.getBoolean(KEY_VPN_ACTIVE, false)
                runOnUiThread { updateDisplay(ping, active) }
            }
        }
    }

    private fun updateDisplay(ping: Int, active: Boolean) {
        tvStatus.text = if (active) "⚡ פעיל" else "⏸ כבוי"
        tvStatus.setTextColor(if (active) 0xFF00C8FF.toInt() else 0xFF888888.toInt())
        tvPing.text = if (ping > 0) "${ping}ms" else "--ms"
        tvPing.setTextColor(when {
            ping <= 0   -> 0xFF888888.toInt()
            ping < 50   -> 0xFF00FFAA.toInt()
            ping < 100  -> 0xFFFF9500.toInt()
            else        -> 0xFFFF3B6B.toInt()
        })
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
