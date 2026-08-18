package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.magen.family.MagenApp;
import com.magen.family.admin.MagenDeviceAdmin;

/**
 * ProtectionWatch — מזהה שהגנה קריטית *כובתה* ומתריע לשרת Magen.
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
    private static final long VPN_SCAN_INTERVAL_MS = 30 * 60_000L;

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
            // רק כשההגנה חמושה (קיים קוד ברית), ולא בזמן חלון תחזוקה/הגדרה.
            // קודם התנאי היה onboarding_done — ולכן מי שלא סיים את המדריך גם
            // לא קיבל שום התראה על כך שההגנה כבויה.
            if (!MagenGuard.isArmed(ctx)) return;
            String activeScope = MagenGuard.activeScope(ctx);
            // רק emergency recovery משבית את כל ההתראות. חלון הרשאה רגיל
            // מדכא אך ורק את הבדיקה של אותה הרשאה — לא את שאר ההגנות.
            if (MagenGuard.allowsAnySensitiveScreen(ctx)) return;

            if (isSafeMode(ctx)) {
                alertOnce(ctx, "safe_mode",
                    "⚠️ המכשיר במצב בטוח (Safe Mode) — הסינון וההגנה מושבתים כרגע.");
            }
            if (!MagenGuard.SCOPE_ACCESSIBILITY.equals(activeScope) && !isAccessibilityOn(ctx)) {
                alertOnce(ctx, "accessibility_off",
                    "⚠️ שירות הנגישות של שומר הברית כבוי — הסינון אינו פעיל.");
            }
            if (!MagenGuard.SCOPE_DEVICE_ADMIN.equals(activeScope) && !MagenDeviceAdmin.isAdminActive(ctx)) {
                alertOnce(ctx, "admin_off",
                    "⚠️ מנהל המכשיר של שומר הברית בוטל — ההגנה מפני הסרה נחלשה.");
            }
            if (!MagenGuard.SCOPE_OVERLAY.equals(activeScope) && !canDrawOverlay(ctx)) {
                alertOnce(ctx, "overlay_off",
                    "⚠️ הרשאת \"הצג מעל אפליקציות\" בוטלה — מסך החסימה לא יוכל לעלות.");
            }

            long lastVpnScan = prefs(ctx).getLong("last_vpn_app_scan", 0);
            long now = System.currentTimeMillis();
            if (now - lastVpnScan >= VPN_SCAN_INTERVAL_MS) {
                prefs(ctx).edit().putLong("last_vpn_app_scan", now).apply();
                AppInstallReceiver.enforceInstalledVpnApps(ctx);
            }
        } catch (Exception ignored) {}
    }

    /** מתריע לשרת, אך לכל היותר פעם ב-30 דק' לכל תנאי (למניעת הצפה). */
    private static void alertOnce(Context ctx, String key, String message) {
        long now = System.currentTimeMillis();
        long last = prefs(ctx).getLong(key, 0);
        if (now - last < REALERT_THROTTLE_MS) return;
        prefs(ctx).edit().putLong(key, now).apply();
        try { NotificationHelper.notifyUrgent(ctx, message); } catch (Exception ignored) {}
    }

    // ---- בדיקות מצב ----

    private static boolean isSafeMode(Context ctx) {
        try { return ctx.getPackageManager().isSafeMode(); }
        catch (Exception e) { return false; }
    }

    private static boolean isAccessibilityOn(Context ctx) {
        try { return com.magen.family.util.AccessibilityState.isMagenEnabled(ctx); }
        catch (Exception e) { return true; }   // בספק — לא מתריעים
    }

    private static boolean canDrawOverlay(Context ctx) {
        try { return Settings.canDrawOverlays(ctx); }
        catch (Exception e) { return true; }
    }
}
