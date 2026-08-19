package com.magen.family.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

public class MagenVpnWatchdog extends BroadcastReceiver {

    private static final String TAG = "MagenWatchdog";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_SCREEN_ON.equals(action) ||
            Intent.ACTION_USER_PRESENT.equals(action) ||
            "android.net.conn.CONNECTIVITY_CHANGE".equals(action)) {

            // הפעל VPN שלנו אם לא פועל
            if (!MagenVpnService.isVpnRunning) {
                Intent vpnPrepare = VpnService.prepare(context);
                if (vpnPrepare == null) {
                    // VPN מאושר — הפעל
                    ServiceRevival.reviveVpn(context);
                    Log.d(TAG, "VPN revive requested");
                }
            }

            // בדוק אם יש VPN חיצוני פעיל
            com.magen.family.server.RealtimeHealthReporter.poke();

            if (isExternalVpnActive(context)) {
                Log.w(TAG, "External VPN detected! Starting KillSwitch");
                // MagenKillSwitch אינו foreground service — startForegroundService()
                // כאן היה מבטיח למערכת startForeground() שלעולם לא הגיע, ולכן
                // גרם ל-ForegroundServiceDidNotStartInTimeException.
                Intent ks = new Intent(context, MagenKillSwitch.class);
                ks.putExtra("require_pin", true);
                MagenKillSwitch.start(context, ks);
            }
        }
    }

    /**
     * בדוק אם VPN חיצוני פעיל (לא VPN שלנו)
     */
    private boolean isExternalVpnActive(Context context) {
        if (!MagenVpnService.isVpnRunning) {
            // אם ה-VPN שלנו לא פועל אבל יש VPN פעיל — זה חיצוני
            ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network[] networks = cm.getAllNetworks();
                for (android.net.Network network : networks) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        return true; // VPN חיצוני!
                    }
                }
            }
        }
        return false;
    }
}
