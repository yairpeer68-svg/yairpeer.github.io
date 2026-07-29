package com.yourname.gamemodevpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
            if (prefs.getBoolean("auto_trigger", false)) {
                context.startService(Intent(context, AutoTriggerService::class.java))
            }
        }
    }
}
