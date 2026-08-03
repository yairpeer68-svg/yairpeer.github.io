package com.magen.family.service;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.magen.family.MagenApp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * SecurityGuard — בדיקות תקינות "קשות".
 *
 * החולשה הכי גדולה במסלול בלי-איפוס: אם USB Debugging דלוק, כל אחד עם מחשב
 * עושה `adb uninstall com.magen.family` ועוקף את הכל תוך שנייה. אפליקציה
 * רגילה *לא יכולה לכבות* את זה (רק Device Owner, דרך DISALLOW_DEBUGGING_FEATURES).
 * לכן אנחנו מזהים ומגיבים — לא מונעים.
 *
 * שני באגים חמורים שתוקנו כאן:
 *
 *   1. strict mode נצחי.
 *      קודם: ADB דלוק -> activateStrict ל-15 דקות, וה-Watchdog רץ כל 15 דקות
 *      ומפעיל שוב. התוצאה הייתה strict mode שלא נגמר לעולם, ובמצב הזה שירות
 *      הנגישות חוסם *כל* אפליקציה חוץ משלנו — כלומר הטלפון הפך לאבן עד
 *      שמישהו כיבה את ADB. עכשיו: התראה בלבד, בלי strict mode.
 *
 *   2. SMS כל 15 דקות, לנצח.
 *      ההערה בקוד טענה ש-BehaviorAnalyzer עושה throttle. הוא לא —
 *      notifyPartner היא passthrough ישירה ל-notifyPartnerUrgent ששולחת SMS.
 *      עכשיו יש throttle אמיתי לכל סוג אזהרה בנפרד.
 */
public class SecurityGuard {

    private static final String TAG = "SecurityGuard";

    private static final String K_LAST_ADB_ALERT = "last_adb_alert_at";
    /** אזהרה על אותו מצב לכל היותר פעם ב-6 שעות. */
    static final long ALERT_INTERVAL_MS = 6 * 60 * 60 * 1000L;

    /** האם ניפוי USB (ADB) דלוק כרגע? */
    public static boolean isAdbEnabled(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(),
                Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** האם אפשרויות מפתחים פתוחות? */
    public static boolean isDevOptionsEnabled(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * בדיקה תקופתית. מחזירה true אם זוהתה בעיית אבטחה.
     *
     * הזיהוי המהיר נעשה ב-TamperWatcher דרך ContentObserver; הבדיקה הזו היא
     * רשת ביטחון למקרה שה-observer לא נרשם.
     */
    public static boolean runSecurityChecks(Context ctx) {
        if (!isAdbEnabled(ctx)) return false;

        Log.w(TAG, "ADB is ON — elevated uninstall risk");

        if (shouldAlert(K_LAST_ADB_ALERT)) {
            NotificationHelper.notifyPartnerUrgent(ctx,
                "🚨 ניפוי USB (ADB) דלוק — סיכון גבוה לעקיפה.\n"
                + "לכיבוי: הגדרות → אפשרויות מפתחים → ניפוי באגים ב-USB");
            markAlerted(K_LAST_ADB_ALERT);
        }
        return true;
    }

    // ---------------- throttle ----------------

    /** האם עבר מספיק זמן מאז ההתראה הקודמת מאותו סוג? */
    static boolean shouldAlert(String key) {
        try {
            long last = MagenApp.getInstance().getPrefs().getLong(key, 0);
            return System.currentTimeMillis() - last >= ALERT_INTERVAL_MS;
        } catch (Exception e) {
            return false;
        }
    }

    static void markAlerted(String key) {
        try {
            MagenApp.getInstance().getPrefs().edit()
                .putLong(key, System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }

    /**
     * דומיינים של ספקי DoH ידועים — נחסמים בשכבת ה-DNS.
     * כולל את ה-canary של Firefox: אם עונים עליו NXDOMAIN, פיירפוקס מכבה
     * DoH מעצמו.
     */
    public static final Set<String> DOH_DOMAINS = new HashSet<>(Arrays.asList(
        "dns.google", "cloudflare-dns.com", "mozilla.cloudflare-dns.com",
        "one.one.one.one", "dns.quad9.net", "doh.opendns.com",
        "dns.nextdns.io", "doh.cleanbrowsing.org", "dns.adguard.com",
        "dns-unfiltered.adguard.com", "doh.dns.sb", "dns.controld.com",
        "use-application-dns.net"
    ));
}
