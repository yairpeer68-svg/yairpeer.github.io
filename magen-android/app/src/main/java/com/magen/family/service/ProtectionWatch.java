package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.text.TextUtils;

import com.magen.family.MagenApp;
import com.magen.family.admin.MagenDeviceAdmin;

/**
 * ProtectionWatch — מזהה שהגנה קריטית *כובתה* ומתריע לשותף האחריות.
 *
 * למה דווקא בדיקת מצב ולא "דופק" מבוסס-זמן:
 *   דופק שמסתמך על אירועי נגישות נותן התראות שווא — כשהמסך כבוי אין אירועים,
 *   ולכן "פער" גדול נראה כמו כיבוי גם כשההגנה בסדר גמור. לעומת זאת ההגדרות
 *   עצמן (שירות נגישות דלוק? מנהל מכשיר פעיל? הרשאת overlay?) הן מצב יציב
 *   ששורד אתחול ושינה — ולכן בדיקתן אמינה וללא התראות שווא.
 *
 * מה נבדק:
 *   • Safe Mode — מצב בטוח משבית שירותי צד-שלישי ברמת המערכת.
 *   • שירות הנגישות כבוי (ליבת הסינון).
 *   • מנהל המכשיר בוטל.
 *   • הרשאת "הצג מעל אפליקציות" בוטלה (מסך החסימה לא יוכל לעלות).
 *
 * לא רושם "נפילה" (לא מאפס רצף) — כדי לא להעניש על כיבוי-שירות זמני ע"י
 * היצרן. רק מתריע. וגם: לא מתריע בזמן חלון תחזוקה (הבעלים משנה ביודעין).
 */
public final class ProtectionWatch {

    private static final String PREFS = "magen_protection_watch";
    private static final long REALERT_THROTTLE_MS = 30 * 60_000L;   // לא יותר מפעם ב-30 דק' לכל תנאי

    private ProtectionWatch() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** בטוח לקריאה מכל מקום — יורד ל-thread רקע לבד (בגלל שליחת רשת). */
    public static void checkAsync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> check(app), "ProtectionWatch").start();
    }

    public static void check(Context ctx) {
        try {
            // רק אחרי שההגדרה הראשונית הושלמה, ולא בזמן חלון תחזוקה של הבעלים.
            if (!MagenApp.getInstance().getPrefs().getBoolean("onboarding_done", false)) return;
            if (MagenGuard.inMaintenance(ctx)) return;

            if (isSafeMode(ctx)) {
                alertOnce(ctx, "safe_mode",
                    "⚠️ המכשיר במצב בטוח (Safe Mode) — הסינון וההגנה מושבתים כרגע.");
            }
            if (!isAccessibilityOn(ctx)) {
                alertOnce(ctx, "accessibility_off",
                    "⚠️ שירות הנגישות של שומר הברית כבוי — הסינון אינו פעיל.");
            }
            if (!MagenDeviceAdmin.isAdminActive(ctx)) {
                alertOnce(ctx, "admin_off",
                    "⚠️ מנהל המכשיר של שומר הברית בוטל — ההגנה מפני הסרה נחלשה.");
            }
            if (!canDrawOverlay(ctx)) {
                alertOnce(ctx, "overlay_off",
                    "⚠️ הרשאת \"הצג מעל אפליקציות\" בוטלה — מסך החסימה לא יוכל לעלות.");
            }
        } catch (Exception ignored) {}
    }

    /** מתריע לשותף, אך לכל היותר פעם ב-30 דק' לכל תנאי (למניעת הצפה). */
    private static void alertOnce(Context ctx, String key, String message) {
        long now = System.currentTimeMillis();
        long last = prefs(ctx).getLong(key, 0);
        if (now - last < REALERT_THROTTLE_MS) return;
        prefs(ctx).edit().putLong(key, now).apply();
        try { NotificationHelper.notifyPartnerUrgent(ctx, message); } catch (Exception ignored) {}
        try { TelegramNotifier.send(ctx, message); } catch (Exception ignored) {}
    }

    // ---- בדיקות מצב ----

    private static boolean isSafeMode(Context ctx) {
        try { return ctx.getPackageManager().isSafeMode(); }
        catch (Exception e) { return false; }
    }

    private static boolean isAccessibilityOn(Context ctx) {
        try {
            String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return !TextUtils.isEmpty(enabled) && enabled.contains(ctx.getPackageName());
        } catch (Exception e) { return true; }   // בספק — לא מתריעים
    }

    private static boolean canDrawOverlay(Context ctx) {
        try { return Settings.canDrawOverlays(ctx); }
        catch (Exception e) { return true; }
    }
}
