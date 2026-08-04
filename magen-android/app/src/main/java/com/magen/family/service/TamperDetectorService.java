package com.magen.family.service;

import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.magen.family.admin.MagenDeviceAdmin;

/**
 * TamperDetectorService — בדיקה תקופתית של תקינות הגנות:
 *  • Device Admin פעיל
 *  • Accessibility פעיל
 *  • ה-VPN שלנו רץ
 *  • לא קיים VPN חיצוני
 * אזעקות נשלחות דרך NotificationHelper. אין KillSwitch אגרסיבי
 * כדי לא לחסום את ההורה מלהזין PIN.
 */
public class TamperDetectorService extends Service {

    private static final String TAG = "TamperDetector";
    private static final long CHECK_INTERVAL_MS = 30_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable checker;
    private boolean lastAccessibility = true;
    private boolean lastAdmin = true;
    private boolean lastVpn = true;
    private boolean lastForeignVpn = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (checker == null) {
            checker = new Runnable() {
                @Override public void run() {
                    runChecks();
                    handler.postDelayed(this, CHECK_INTERVAL_MS);
                }
            };
            handler.post(checker);
        }
        return START_STICKY;
    }

    private void runChecks() {
        boolean adminOn = isDeviceAdminActive();
        boolean accOn = isAccessibilityEnabled();
        boolean vpnOn = MagenVpnService.isVpnRunning;
        boolean foreignVpn = isForeignVpnActive();

        StringBuilder issues = new StringBuilder();

        if (!adminOn && lastAdmin) issues.append("\n• Device Admin הושבת");
        if (!accOn && lastAccessibility) {
            issues.append("\n• שירות נגישות הושבת");
            // ליבת הסינון מתה — נועלים את המסך האמיתי של המערכת.
            // TamperWatcher בדרך כלל יקדים אותנו (ContentObserver מיידי),
            // אבל זו רשת ביטחון אם ה-observer לא נרשם.
            MagenDeviceAdmin.lockDeviceNow(this);
        }
        if (!vpnOn && lastVpn) {
            issues.append("\n• ה-VPN שלנו הושבת");
            startService(new Intent(this, MagenVpnService.class));
        }

        if (foreignVpn) {
            Log.w(TAG, "🚨 Foreign VPN detected");
            if (!lastForeignVpn) {
                // VPN חיצוני = הסינון שלנו מבוטל. נועלים ומתריעים (יקר ורועש).
                MagenDeviceAdmin.lockDeviceNow(this);
                NotificationHelper.notifyPartnerUrgent(this,
                    "🚨 VPN חיצוני זוהה — ההגנה נעקפת! המכשיר ננעל.");
            }
            // נסיון מתמשך להחזיר את ה-VPN שלנו (יצליח כשהחיצוני יכובה)
            startService(new Intent(this, MagenVpnService.class));
        }

        if (issues.length() > 0) {
            NotificationHelper.notifyPartnerUrgent(this, "⚠️ הגנה הושבתה:" + issues);
        }

        lastAdmin = adminOn;
        lastAccessibility = accOn;
        lastVpn = vpnOn;
        lastForeignVpn = foreignVpn;
    }

    /**
     * בדיקה אם VPN שאינו שלנו פעיל.
     */
    private boolean isForeignVpnActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(net);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    if (!MagenVpnService.isVpnRunning) return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "isForeignVpnActive: " + e.getMessage());
        }
        return false;
    }

    private boolean isDeviceAdminActive() {
        try {
            DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            return dpm.isAdminActive(MagenDeviceAdmin.getComponentName(this));
        } catch (Exception e) { return false; }
    }

    private boolean isAccessibilityEnabled() {
        try {
            String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return !TextUtils.isEmpty(enabled) && enabled.contains(getPackageName());
        } catch (Exception e) { return false; }
    }

    @Override
    public void onDestroy() {
        if (checker != null) handler.removeCallbacks(checker);
        startService(new Intent(this, TamperDetectorService.class));
        super.onDestroy();
    }
}
