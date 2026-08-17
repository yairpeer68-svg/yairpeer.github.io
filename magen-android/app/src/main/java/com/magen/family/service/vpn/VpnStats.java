package com.magen.family.service.vpn;

import com.magen.family.MagenApp;

import java.util.concurrent.atomic.AtomicLong;

/**
 * VpnStats — מונים קלים לשכבת הרשת.
 *
 * מוחזקים בזיכרון (AtomicLong) ולא ב-SharedPreferences, כי הם מתעדכנים
 * אלפי פעמים בדקה — כתיבה ל-prefs בקצב הזה הורגת את הביצועים ואת הדיסק.
 * הערך המצטבר נשמר רק כשהדוח נבנה.
 */
public final class VpnStats {

    private static final AtomicLong blockedDomains = new AtomicLong();
    private static final AtomicLong blockedSni     = new AtomicLong();
    private static final AtomicLong blockedQuic    = new AtomicLong();
    private static final AtomicLong blockedDot     = new AtomicLong();

    private static volatile String lastBlockedHost = "";

    private VpnStats() {}

    public static void countBlockedDomain(String host) {
        blockedDomains.incrementAndGet();
        lastBlockedHost = host;
        bumpAppCounter();
    }

    public static void countBlockedSni(String host) {
        blockedSni.incrementAndGet();
        lastBlockedHost = host;
        bumpAppCounter();
    }

    public static void countBlockedQuic() { blockedQuic.incrementAndGet(); }
    public static void countBlockedDot()  { blockedDot.incrementAndGet(); }

    /** מעדכן את המונה הראשי שמוצג למשתמש — עם דגימה כדי לא להציף. */
    private static void bumpAppCounter() {
        try {
            MagenApp app = MagenApp.getInstance();
            if (app != null) app.incrementBlockedCount();
        } catch (Exception ignored) {}
    }

    public static long getBlockedDomains() { return blockedDomains.get(); }
    public static long getBlockedSni()     { return blockedSni.get(); }
    public static long getBlockedQuic()    { return blockedQuic.get(); }
    public static long getBlockedDot()     { return blockedDot.get(); }
    public static String getLastBlockedHost() { return lastBlockedHost; }

    public static String summary() {
        return "DNS:" + blockedDomains.get()
             + " SNI:" + blockedSni.get()
             + " QUIC:" + blockedQuic.get()
             + " DoT:" + blockedDot.get();
    }
}
