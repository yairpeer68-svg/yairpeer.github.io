package com.magen.family.service;

import android.os.SystemClock;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.magen.family.admin.MagenDeviceAdmin;

/**
 * TamperWatcher — זיהוי *מיידי* של השבתת הגנה, במקום polling.
 *
 * מה היה קודם:
 *   TamperDetectorService בדק כל 30 שניות, ו-SecurityGuard כל 15 דקות.
 *   כלומר מי שכיבה את שירות הנגישות קיבל חלון של עד חצי דקה (ובפועל
 *   הרבה יותר, כי השירות עצמו נהרג יחד איתו) — מספיק בקלות כדי להיכנס
 *   להגדרות ולהסיר את האפליקציה.
 *
 * מה עכשיו:
 *   ContentObserver על שתי הגדרות מערכת. המערכת מודיעה לנו ברגע שהערך
 *   משתנה — בלי לולאה, בלי סוללה, בלי חלון.
 *
 *     ENABLED_ACCESSIBILITY_SERVICES  -> כיבוי הנגישות = ליבת הסינון מתה
 *     ADB_ENABLED                     -> ניפוי USB נדלק = סיכון להסרה
 *
 *   התגובה על כיבוי נגישות היא dpm.lockNow() — נעילת מסך הנעילה האמיתי של
 *   המערכת. זה חזק בהרבה מ-overlay: אי אפשר לעקוף אותו ב-BACK או ב-HOME.
 */
public final class TamperWatcher {

    private static final String TAG = "TamperWatcher";

    private static ContentObserver accessibilityObserver;
    private static ContentObserver adbObserver;
    private static boolean registered = false;

    /** אנטי-רעש: אל תנעל/תתריע יותר מפעם ב-X על אותו אירוע. */
    private static final long REACT_COOLDOWN_MS = 60_000L;
    private static long lastAccessibilityReact = 0;
    private static long lastAdbReact = 0;

    private TamperWatcher() {}

    public static synchronized void start(Context context) {
        if (registered) return;
        final Context ctx = context.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());

        accessibilityObserver = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) {
                onAccessibilityChanged(ctx);
            }
        };

        adbObserver = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) {
                onAdbChanged(ctx);
            }
        };

        try {
            ctx.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false, accessibilityObserver);
            ctx.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.ADB_ENABLED),
                false, adbObserver);
            registered = true;
            Log.d(TAG, "watching accessibility + adb settings");
        } catch (Exception e) {
            Log.e(TAG, "registerContentObserver failed: " + e.getMessage());
        }
    }

    public static synchronized void stop(Context context) {
        Context ctx = context.getApplicationContext();
        try {
            if (accessibilityObserver != null)
                ctx.getContentResolver().unregisterContentObserver(accessibilityObserver);
            if (adbObserver != null)
                ctx.getContentResolver().unregisterContentObserver(adbObserver);
        } catch (Exception ignored) {}
        registered = false;
    }

    // ---------------- תגובות ----------------

    private static void onAccessibilityChanged(Context ctx) {
        if (isOurAccessibilityEnabled(ctx)) return;   // הודלק/נשאר דלוק — הכל טוב

        long now = SystemClock.elapsedRealtime();
        if (now - lastAccessibilityReact < REACT_COOLDOWN_MS) return;
        lastAccessibilityReact = now;

        Log.w(TAG, "accessibility service was turned OFF");

        // נעילת המסך האמיתית של המערכת — לא overlay שנעקף ב-BACK.
        MagenDeviceAdmin.lockDeviceNow(ctx);

        // שכבה שנייה: גם אחרי פתיחת מסך הנעילה, Magen נשאר על מסך עצירה
        // ודורש PIN. כך כיבוי Accessibility לא נותן חלון חופשי להסרת ההגנה.
        try {
            android.content.Intent ks = new android.content.Intent(ctx, MagenKillSwitch.class);
            ks.putExtra("require_pin", true);
            MagenKillSwitch.start(ctx, ks);
        } catch (Exception e) {
            Log.e(TAG, "KillSwitch after accessibility off failed: " + e.getMessage());
        }

        NotificationHelper.notifyUrgent(ctx,
            "🚨 שירות הנגישות של שומר הברית כובה — הסינון אינו פעיל.");
        com.magen.family.server.ServerEventReporter.report(ctx,
            "ACCESSIBILITY_DISABLED", "CRITICAL", "Accessibility service disabled");
        ActivityReporter.recordSecurityAlert(ctx);
    }

    private static void onAdbChanged(Context ctx) {
        if (!SecurityGuard.isAdbEnabled(ctx)) return;   // כובה — טוב

        long now = SystemClock.elapsedRealtime();
        if (now - lastAdbReact < REACT_COOLDOWN_MS) return;
        lastAdbReact = now;

        Log.w(TAG, "ADB was turned ON");
        NotificationHelper.notifyUrgent(ctx,
            "🚨 ניפוי USB (ADB) הודלק — סיכון גבוה להסרת האפליקציה.\n"
            + "לכיבוי: הגדרות → אפשרויות מפתחים → ניפוי באגים ב-USB");
        ActivityReporter.recordSecurityAlert(ctx);
    }

    public static boolean isOurAccessibilityEnabled(Context ctx) {
        return com.magen.family.util.AccessibilityState.isMagenEnabled(ctx);
    }
}
