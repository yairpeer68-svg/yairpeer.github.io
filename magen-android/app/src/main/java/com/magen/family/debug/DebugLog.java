package com.magen.family.debug;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import com.magen.family.MagenApp;
import com.magen.family.admin.MagenDeviceAdmin;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * DebugLog — יומן שמתעד כל הזמן מה קורה באפליקציה.
 *
 * למה זה קיים:
 *   כשמשהו לא עובד ("אפשר לשנות הרשאות בלי אישור", "המסך לא נפתח") אין
 *   דרך לדעת מה האפליקציה חשבה באותו רגע. היומן הזה רושם את האירועים
 *   החשובים — הפעלת הגנה, חסימות, ניסיונות שיבוש, מסכי הגדרות שנפתחו,
 *   ומצב ההרשאות — וניתן לשתף אותו בלחיצה אחת לצורך אבחון.
 *
 * מדוע בזיכרון ולא בקובץ בלבד:
 *   הכתיבה קורית מנתיבים רגישים לביצועים (שירות הנגישות מקבל אירועים
 *   עשרות פעמים בשנייה). לכן מחזיקים טבעת בזיכרון וכותבים לדיסק רק
 *   מדי פעם — כדי שהתיעוד לא יאט את הסינון עצמו.
 */
public final class DebugLog {

    private static final int MAX_LINES = 400;
    private static final String PREFS = "magen_debug";
    private static final String KEY_LINES = "lines";

    private static final Deque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TS =
        new SimpleDateFormat("HH:mm:ss", Locale.US);

    private static volatile boolean loaded = false;
    private static int sinceFlush = 0;

    private DebugLog() {}

    /** רושם אירוע. בטוח לקריאה מכל thread ומכל מקום. */
    public static void log(Context ctx, String tag, String message) {
        try {
            String line = TS.format(new Date()) + "  [" + tag + "] " + message;
            synchronized (LINES) {
                ensureLoaded(ctx);
                LINES.addLast(line);
                while (LINES.size() > MAX_LINES) LINES.removeFirst();
                sinceFlush++;
                // כתיבה לדיסק כל 10 שורות — מספיק כדי לשרוד קריסה, בלי
                // להאט את נתיב הסינון.
                if (sinceFlush >= 10) {
                    flushLocked(ctx);
                }
            }
        } catch (Exception ignored) {}
    }

    /** מוודא שהכל נשמר (נקרא לפני שיתוף). */
    public static void flush(Context ctx) {
        synchronized (LINES) {
            try { flushLocked(ctx); } catch (Exception ignored) {}
        }
    }

    public static void clear(Context ctx) {
        synchronized (LINES) {
            LINES.clear();
            sinceFlush = 0;
            try {
                prefs(ctx).edit().remove(KEY_LINES).apply();
            } catch (Exception ignored) {}
        }
    }

    /** היומן כטקסט, עם כותרת מצב מלאה — זה מה שמשתפים. */
    public static String buildReport(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("שומר הברית — דוח אבחון\n");
        sb.append("========================\n");
        sb.append(snapshot(ctx));
        sb.append("\n----- יומן אירועים -----\n");
        synchronized (LINES) {
            ensureLoaded(ctx);
            if (LINES.isEmpty()) {
                sb.append("(אין אירועים עדיין)\n");
            } else {
                for (String l : LINES) sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    /** מצב ההגנה כרגע — התמונה שהכי חשובה לאבחון. */
    public static String snapshot(Context ctx) {
        StringBuilder sb = new StringBuilder();
        String buildId = "?";
        try {
            int id = ctx.getResources().getIdentifier("build_id", "string", ctx.getPackageName());
            if (id != 0) buildId = ctx.getString(id);
        } catch (Exception ignored) {}

        sb.append("זמן:            ")
          .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()))
          .append('\n');
        sb.append("build:          ").append(buildId).append('\n');
        sb.append("מכשיר:          ").append(Build.MANUFACTURER).append(' ')
          .append(Build.MODEL).append('\n');
        sb.append("אנדרואיד:       ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");

        sb.append("קוד ברית מוגדר: ").append(yn(hasPin(ctx))).append('\n');
        sb.append("הגנה חמושה:     ")
          .append(yn(com.magen.family.service.MagenGuard.isArmed(ctx)))
          .append("   <-- אם 'לא', ההגנה העצמית כבויה!\n");
        sb.append("חלון תחזוקה:    ")
          .append(yn(com.magen.family.service.MagenGuard.inMaintenance(ctx)))
          .append("   <-- אם 'כן', ההגנה מושהית זמנית\n");
        sb.append("מדריך הושלם:    ").append(yn(onboardingDone(ctx))).append('\n');
        sb.append("שירות נגישות:   ").append(yn(accessibilityOn(ctx))).append('\n');
        sb.append("מנהל מכשיר:     ").append(yn(adminActive(ctx))).append('\n');
        sb.append("תצוגה מעל:      ").append(yn(overlayOn(ctx))).append('\n');
        sb.append("פטור סוללה:     ").append(yn(batteryExempt(ctx))).append('\n');
        sb.append("VPN פעיל:       ")
          .append(yn(com.magen.family.service.MagenVpnService.isVpnRunning)).append('\n');
        return sb.toString();
    }

    // ---------------- בדיקות מצב ----------------

    private static String yn(boolean b) { return b ? "כן" : "לא"; }

    private static boolean hasPin(Context ctx) {
        try {
            String pin = ctx.getSharedPreferences(MagenApp.PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(MagenApp.KEY_PIN, "");
            return pin != null && !pin.isEmpty();
        } catch (Exception e) { return false; }
    }

    private static boolean onboardingDone(Context ctx) {
        try {
            return ctx.getSharedPreferences(MagenApp.PREFS_NAME, Context.MODE_PRIVATE)
                      .getBoolean("onboarding_done", false);
        } catch (Exception e) { return false; }
    }

    private static boolean accessibilityOn(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return !TextUtils.isEmpty(enabled) && enabled.contains(ctx.getPackageName());
        } catch (Exception e) { return false; }
    }

    private static boolean adminActive(Context ctx) {
        try { return MagenDeviceAdmin.isAdminActive(ctx); } catch (Exception e) { return false; }
    }

    private static boolean overlayOn(Context ctx) {
        try { return Settings.canDrawOverlays(ctx); } catch (Exception e) { return false; }
    }

    private static boolean batteryExempt(Context ctx) {
        try {
            android.os.PowerManager pm =
                (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) { return false; }
    }

    // ---------------- אחסון ----------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void ensureLoaded(Context ctx) {
        if (loaded) return;
        loaded = true;
        try {
            String raw = prefs(ctx).getString(KEY_LINES, "");
            if (!raw.isEmpty()) {
                for (String l : raw.split("\n")) {
                    if (!l.trim().isEmpty()) LINES.addLast(l);
                }
            }
        } catch (Exception ignored) {}
    }

    private static void flushLocked(Context ctx) {
        sinceFlush = 0;
        StringBuilder sb = new StringBuilder();
        for (String l : LINES) sb.append(l).append('\n');
        prefs(ctx).edit().putString(KEY_LINES, sb.toString()).apply();
    }
}
