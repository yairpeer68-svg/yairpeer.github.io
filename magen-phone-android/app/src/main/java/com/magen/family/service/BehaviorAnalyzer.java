package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.magen.family.MagenApp;

import java.util.Calendar;

/**
 * BehaviorAnalyzer — ניתוח דפוסי שימוש חשודים + escalation logic.
 *
 * שיפורים מהגרסה הקודמת:
 *   • אין כפילויות של מתודות/שדות (הקוד הקודם לא היה מתקמפל).
 *   • Escalation: מאגד אירועים ושולח לשרת רק ברגעים חשובים.
 *   • זמני strict mode חכמים יותר (לא 30 דקות באמצע יום הלימודים).
 */
public class BehaviorAnalyzer {

    private static final String TAG = "BehaviorAnalyzer";

    public enum SuspiciousPattern {
        NIGHT_USAGE,
        MANY_BLOCKS_SHORT_TIME,
        VPN_BYPASS_ATTEMPT,
        SETTINGS_ACCESS,
        RAPID_APP_SWITCHING,
        UNINSTALL_ATTEMPT
    }

    // ===== מפתחות SharedPreferences =====
    private static final String K_NIGHT_ATTEMPTS    = "night_attempts";
    private static final String K_BLOCK_WINDOW_AT   = "block_window_start";
    private static final String K_BLOCKS_IN_WINDOW  = "blocks_in_window";
    private static final String K_STRICT_UNTIL      = "strict_mode_until";
    private static final String K_LAST_ALERT_AT     = "last_parent_alert_at";
    private static final String K_PENDING_DIGEST    = "pending_alert_digest";

    // ===== ספים =====
    private static final long BLOCK_WINDOW_MS         = 5 * 60 * 1000L;   // 5 דק
    private static final int  BLOCKS_TRIGGER_STRICT   = 8;                // 8 חסימות = strict
    private static final long STRICT_DURATION_MS      = 15 * 60 * 1000L;  // 15 דק (לא 30)
    private static final long MIN_ALERT_INTERVAL_MS   = 30 * 60 * 1000L;  // אזהרה לשרת מקסימום אחת ל-30 דק
    private static final int  RAPID_SWITCH_THRESHOLD  = 8;                // 8 (לא 5) — פחות false positive
    private static final long RAPID_SWITCH_WINDOW_MS  = 3000;             // 3 שניות

    private final SharedPreferences prefs;
    private OnSuspiciousListener listener;

    // משתני מצב in-memory בלבד (לא persistent — מתאפסים בריסטרט)
    private long lastAppSwitchAt = 0;
    private int  rapidSwitchCount = 0;

    public interface OnSuspiciousListener {
        void onSuspicious(SuspiciousPattern pattern, String details);
    }

    public BehaviorAnalyzer(Context ctx) {
        this.prefs = MagenApp.getInstance().getPrefs();
    }

    public void setListener(OnSuspiciousListener l) {
        this.listener = l;
    }

    // ---------------- שימוש בלילה ----------------

    public void checkNightUsage() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 23 || hour < 6) {
            int attempts = prefs.getInt(K_NIGHT_ATTEMPTS, 0) + 1;
            prefs.edit().putInt(K_NIGHT_ATTEMPTS, attempts).apply();
            Log.w(TAG, "Night attempt #" + attempts + " @" + hour + ":00");

            // התראה רק אחרי 5 ניסיונות (לא אחרי 3)
            if (attempts >= 5 && listener != null) {
                fire(SuspiciousPattern.NIGHT_USAGE,
                    "שימוש לילי — " + attempts + " ניסיונות אחרי שעת השינה");
            }
        }
    }

    // ---------------- ספייק חסימות ----------------

    public void checkBlockingSpike() {
        long now = System.currentTimeMillis();
        long windowStart = prefs.getLong(K_BLOCK_WINDOW_AT, now);
        int  countNow    = prefs.getInt(K_BLOCKS_IN_WINDOW, 0) + 1;

        if (now - windowStart > BLOCK_WINDOW_MS) {
            // התחל חלון חדש
            prefs.edit()
                .putLong(K_BLOCK_WINDOW_AT, now)
                .putInt(K_BLOCKS_IN_WINDOW, 1)
                .apply();
            return;
        }

        prefs.edit().putInt(K_BLOCKS_IN_WINDOW, countNow).apply();

        if (countNow >= BLOCKS_TRIGGER_STRICT) {
            activateStrictMode("ספייק חסימות: " + countNow + " ב-5 דק'");
            // אפס את החלון כדי לא להפעיל שוב מיד
            prefs.edit().putInt(K_BLOCKS_IN_WINDOW, 0).apply();
        }
    }

    // ---------------- ניסיון עקיפת VPN ----------------

    public void recordVpnBypassAttempt() {
        MagenApp.getInstance().incrementVpnAttempts();
        Log.w(TAG, "⚠️ VPN bypass attempt");
        fire(SuspiciousPattern.VPN_BYPASS_ATTEMPT,
            "ניסיון להפעיל VPN חיצוני");
    }

    // ---------------- ניסיון הסרת אדמין / הסרת אפליקציה ----------------

    public void recordUninstallAttempt() {
        Log.w(TAG, "🚨 Uninstall attempt detected");
        // התראה דחופה — תמיד נשלחת, גם אם זה אומר לעקוף את ה-throttle
        if (listener != null) {
            listener.onSuspicious(SuspiciousPattern.UNINSTALL_ATTEMPT,
                "🚨 ניסיון להסיר את אפליקציית שומר הברית");
        }
        NotificationHelper.notifyUrgent(MagenApp.getInstance(),
            "🚨 ניסיון להסיר את שומר הברית!");
        // ניסיון עקיפה אמיתי שובר את הרצף (בניגוד לחסימת תוכן, שהיא הצלחה)
        try {
            com.magen.family.covenant.StreakManager.recordSlip(
                MagenApp.getInstance(), "ניסיון הסרה");
        } catch (Exception ignored) {}
    }

    // ---------------- החלפת אפליקציות מהירה ----------------

    public void recordAppSwitch(String fromPkg, String toPkg) {
        long now = System.currentTimeMillis();
        if (now - lastAppSwitchAt < RAPID_SWITCH_WINDOW_MS) {
            rapidSwitchCount++;
            if (rapidSwitchCount >= RAPID_SWITCH_THRESHOLD) {
                Log.w(TAG, "Rapid switching — " + rapidSwitchCount + " in " + RAPID_SWITCH_WINDOW_MS + "ms");
                rapidSwitchCount = 0;
                // לא הופכים אוטומטית ל-strict; רק מסמנים. strict רק כשיש ספייק חסימות בפועל.
                fire(SuspiciousPattern.RAPID_APP_SWITCHING,
                    "החלפת אפליקציות מהירה — ייתכן ניסיון עקיפה");
            }
        } else {
            rapidSwitchCount = 0;
        }
        lastAppSwitchAt = now;
    }

    // ---------------- strict mode ----------------

    private void activateStrictMode(String reason) {
        long until = System.currentTimeMillis() + STRICT_DURATION_MS;
        prefs.edit().putLong(K_STRICT_UNTIL, until).apply();
        Log.w(TAG, "🔴 Strict mode activated: " + reason);
        fire(SuspiciousPattern.MANY_BLOCKS_SHORT_TIME,
            "מצב מחמיר הופעל ל-15 דק׳: " + reason);
    }

    public static boolean isStrictMode() {
        long until = MagenApp.getInstance().getPrefs().getLong(K_STRICT_UNTIL, 0);
        return System.currentTimeMillis() < until;
    }

    // ---------------- escalation — כל ההתראות עוברות פה ----------------

    private void fire(SuspiciousPattern pattern, String details) {
        if (listener != null) {
            listener.onSuspicious(pattern, details);
        }
        scheduleParentAlert(details);
    }

    /**
     * אגירת התראות — לא שולחים אירוע על כל שינוי רגעי.
     * אם עברה לפחות חצי שעה מההתראה הקודמת → שולחים digest של מה שהצטבר.
     */
    private void scheduleParentAlert(String detail) {
        long now = System.currentTimeMillis();
        long lastAlert = prefs.getLong(K_LAST_ALERT_AT, 0);

        // אגור את הפרט בתוך digest
        String digest = prefs.getString(K_PENDING_DIGEST, "");
        if (!digest.isEmpty()) digest += "\n";
        digest += "• " + detail;

        if (now - lastAlert >= MIN_ALERT_INTERVAL_MS) {
            // שלח עכשיו
            NotificationHelper.notifyDigest(MagenApp.getInstance(), digest);
            prefs.edit()
                .putString(K_PENDING_DIGEST, "")
                .putLong(K_LAST_ALERT_AT, now)
                .apply();
        } else {
            // אגור להמשך
            prefs.edit().putString(K_PENDING_DIGEST, digest).apply();
        }
    }

    /**
     * נקרא ע"י flush job כל כמה זמן — שולח digest תקופתי אם יש מה לשלוח.
     */
    public static void flushPendingDigest(Context ctx) {
        SharedPreferences p = MagenApp.getInstance().getPrefs();
        String digest = p.getString(K_PENDING_DIGEST, "");
        if (digest.isEmpty()) return;

        long lastAlert = p.getLong(K_LAST_ALERT_AT, 0);
        if (System.currentTimeMillis() - lastAlert < MIN_ALERT_INTERVAL_MS) return;

        NotificationHelper.notifyDigest(ctx, digest);
        p.edit()
            .putString(K_PENDING_DIGEST, "")
            .putLong(K_LAST_ALERT_AT, System.currentTimeMillis())
            .apply();
    }

    /**
     * עזר — שליחת התראת אבטחה לשרת.
     */
    public static void notifyAlert(Context ctx, String message) {
        NotificationHelper.notifyUrgent(ctx, message);
    }
}
