package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.magen.family.admin.MagenDeviceAdmin;

import java.net.NetworkInterface;
import java.util.Collections;

/**
 * HotspotGuard — זיהוי ותגובה על שיתוף אינטרנט (hotspot).
 *
 * מה זה עושה ומה לא (ישר לגמרי):
 *   אפליקציה רגילה *אינה יכולה* לכבות hotspot של המערכת — זה דורש הרשאת מערכת
 *   או Device Owner. מה שכן אפשר: לזהות שהוא דלוק ולהגיב (נעילה + התראה),
 *   בדיוק במודל של שאר ההגנות. המתג באפליקציה קובע האם להפעיל את התגובה.
 *
 * איך מזהים בלי הרשאה:
 *   כשמפעילים hotspot, המערכת מרימה ממשק רשת ייעודי (ap0 / wlan1 / rndis /
 *   swlan0, תלוי-יצרן) עם כתובת IP. סורקים את ממשקי הרשת ומחפשים ממשק כזה
 *   פעיל. זו הערכה — לא כל היצרנים זהים — אבל היא לא דורשת שום הרשאה.
 */
public final class HotspotGuard {

    private static final String TAG = "HotspotGuard";
    private static final String K_LAST_ALERT = "last_hotspot_alert_at";

    // ממשקי tethering נפוצים לפי יצרן
    private static final String[] TETHER_IFACES = {
        "ap0", "ap1", "wlan1", "swlan0", "rndis0", "usb0", "bt-pan"
    };

    private HotspotGuard() {}

    public static boolean isPolicyEnabled(Context ctx) {
        return com.magen.family.service.vpn.VpnPolicy.blockHotspot();
    }

    /** האם נראה שיש כרגע שיתוף אינטרנט פעיל? */
    public static boolean isHotspotActive() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                for (String t : TETHER_IFACES) {
                    if (name.startsWith(t) && hasAddress(ni)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isHotspotActive: " + e.getMessage());
        }
        return false;
    }

    private static boolean hasAddress(NetworkInterface ni) {
        try {
            return ni.getInetAddresses().hasMoreElements();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * בדיקה תקופתית — נקראת מה-Watchdog. אם המדיניות דלוקה ויש hotspot פעיל,
     * נועלים את המסך ומדווחים לשרת (עם throttle כדי לא להציף).
     */
    public static void check(Context ctx) {
        if (!isPolicyEnabled(ctx)) return;
        if (!isHotspotActive()) return;

        Log.w(TAG, "hotspot detected while policy is ON");
        MagenDeviceAdmin.lockDeviceNow(ctx);

        SharedPreferences p = com.magen.family.MagenApp.getInstance().getPrefs();
        long now = System.currentTimeMillis();
        long last = p.getLong(K_LAST_ALERT, 0);
        if (now - last >= 30 * 60 * 1000L) {
            p.edit().putLong(K_LAST_ALERT, now).apply();
            NotificationHelper.notifyUrgent(ctx,
                "🚨 זוהה שיתוף אינטרנט (hotspot) — ייתכן ניסיון לעקוף את הסינון דרך מכשיר שני.");
            ActivityReporter.recordSecurityAlert(ctx);
        }
    }
}
