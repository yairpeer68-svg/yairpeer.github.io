package com.magen.family.service;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import com.magen.family.MagenApp;

import java.io.File;
import java.security.MessageDigest;

/**
 * IntegrityGuard — זיהוי סביבה עוינת ומניפולציות שעוקפות את ההגנה:
 *   1. Root  — מכשיר מרוט עוקף כמעט הכל. מזהים ומתריעים.
 *   2. חתימת APK — מזהה אם מישהו קימפל מחדש גרסה "פרוצה" והתקין אותה.
 *   3. שינוי שעון — מי שמזיז את שעון המערכת עוקף strict-mode/geofence.
 *      אנחנו מבססים "זמן אמין" על elapsedRealtime (מונוטוני) במקום שעון קיר.
 *
 * הערות ישרות:
 *   • זיהוי root הוא משחק חתול-ועכבר; Magisk Hide/DenyList יכול להסתיר.
 *     זו שכבת הרתעה, לא הוכחה. עדיין שווה — עוצר את הרוב.
 *   • בדיקת החתימה דורשת שתמלאי את EXPECTED_SIG_SHA256 עם ה-hash של תעודת
 *     ה-release שלך (הוראה למטה). כל עוד ריק — הבדיקה מדלגת.
 */
public class IntegrityGuard {

    private static final String TAG = "IntegrityGuard";

    // ⬇️ מלאי כאן את ה-SHA-256 של תעודת ה-release (ראה WIRING.md איך משיגים).
    // דוגמה: "A1:B2:..." — או ריק כדי לדלג על הבדיקה.
    private static final String EXPECTED_SIG_SHA256 = "";

    private static final String K_BASE_WALL     = "time_base_wall";
    private static final String K_BASE_ELAPSED  = "time_base_elapsed";
    /**
     * סטייה מותרת בין שעון הקיר לזמן המונוטוני.
     *
     * 5 דקות היו צרות מדי: אחרי אתחול, סנכרון NTP מתקן את השעון בקפיצה
     * שיכולה לעבור בקלות 5 דקות אם סוללת ה-RTC חלשה — וזה נראה בדיוק כמו
     * מניפולציה. שעה נותנת מרווח סביר בלי לפספס שינוי ידני משמעותי
     * (מי שמזיז שעון כדי לעקוף הגבלה מזיז אותו בשעות, לא בדקות).
     */
    private static final long CLOCK_TOLERANCE_MS = 60 * 60 * 1000L;

    // ---------------- בדיקה מרכזית ----------------

    private static final String K_ALERT_ROOT  = "last_root_alert_at";
    private static final String K_ALERT_SIG   = "last_sig_alert_at";
    private static final String K_ALERT_CLOCK = "last_clock_alert_at";

    /**
     * בדיקות תקינות תקופתיות.
     *
     * כל התראה עוברת throttle (SecurityGuard.shouldAlert). קודם כל בדיקה
     * שנכשלה שלחה SMS דחוף בכל מחזור Watchdog — כלומר כל 15 דקות, לנצח,
     * על מכשיר מרוט שלא ישתנה. זה הציף את ההורה ועלה כסף.
     */
    public static boolean runIntegrityChecks(Context ctx) {
        boolean issue = false;

        if (isDeviceRooted()) {
            Log.w(TAG, "Root detected");
            if (SecurityGuard.shouldAlert(K_ALERT_ROOT)) {
                NotificationHelper.notifyPartnerUrgent(ctx,
                    "🚨 זוהה מכשיר מרוט (root) — ההגנה עלולה להיעקף.");
                SecurityGuard.markAlerted(K_ALERT_ROOT);
            }
            issue = true;
        }

        if (isSignatureTampered(ctx)) {
            Log.w(TAG, "APK signature mismatch");
            if (SecurityGuard.shouldAlert(K_ALERT_SIG)) {
                NotificationHelper.notifyPartnerUrgent(ctx,
                    "🚨 חתימת האפליקציה אינה תואמת — ייתכן שהותקנה גרסה פרוצה.");
                SecurityGuard.markAlerted(K_ALERT_SIG);
            }
            issue = true;
        }

        if (isClockTampered(ctx)) {
            Log.w(TAG, "System clock tampering suspected");
            if (SecurityGuard.shouldAlert(K_ALERT_CLOCK)) {
                NotificationHelper.notifyPartnerUrgent(ctx,
                    "⚠️ זוהה שינוי חריג בשעון המערכת.");
                SecurityGuard.markAlerted(K_ALERT_CLOCK);
            }
            // אחרי דיווח מיישרים את קו הבסיס, אחרת כל בדיקה עתידית תיכשל שוב
            initClockBaseline(ctx);
            issue = true;
        }

        return issue;
    }

    // ---------------- 1. Root ----------------

    public static boolean isDeviceRooted() {
        return checkTestKeys() || checkSuBinaries() || checkRootApps()
            || checkRootPaths() || checkMagisk();
    }

    /**
     * זיהוי Magisk/Zygisk ספציפית — מעבר לבדיקת su הכללית.
     *
     * Magisk הוא כלי ה-root הנפוץ ביותר והוא משתדל להסתתר (MagiskHide/DenyList),
     * ולכן זו שכבת הרתעה ולא הוכחה. עדיין תופסת התקנות סטנדרטיות: נתיבי
     * Magisk, חבילות ה-manager (כולל שמות מוסווים), ומאפייני מערכת של Zygisk.
     */
    private static boolean checkMagisk() {
        String[] magiskPaths = {
            "/sbin/.magisk", "/cache/.disable_magisk", "/dev/.magisk.unblock",
            "/cache/magisk.log", "/data/adb/magisk", "/data/adb/modules",
            "/data/adb/magisk.db", "/sbin/magisk", "/system/bin/magisk"
        };
        for (String p : magiskPaths) {
            try { if (new File(p).exists()) return true; } catch (Exception ignored) {}
        }

        String[] magiskPkgs = {
            "com.topjohnwu.magisk", "io.github.huskydg.magisk",
            "io.github.vvb2060.magisk", "com.zzzmode.appopsx"
        };
        try {
            PackageManager pm = MagenApp.getInstance().getPackageManager();
            for (String pkg : magiskPkgs) {
                try { pm.getPackageInfo(pkg, 0); return true; }
                catch (PackageManager.NameNotFoundException ignored) {}
            }
        } catch (Exception ignored) {}

        // Zygisk מדליק לעיתים מאפיין מערכת
        try {
            String zygisk = System.getProperty("ro.dalvik.vm.native.bridge");
            if (zygisk != null && zygisk.toLowerCase().contains("magisk")) return true;
        } catch (Exception ignored) {}

        return false;
    }

    private static boolean checkTestKeys() {
        String tags = Build.TAGS;
        return tags != null && tags.contains("test-keys");
    }

    private static boolean checkSuBinaries() {
        String[] paths = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/app/Superuser.apk", "/system/sd/xbin/su",
            "/system/bin/failsafe/su", "/data/local/xbin/su",
            "/data/local/bin/su", "/data/local/su",
            "/su/bin/su", "/system/xbin/mu", "/vendor/bin/su"
        };
        for (String p : paths) {
            try { if (new File(p).exists()) return true; } catch (Exception ignored) {}
        }
        return false;
    }

    private static boolean checkRootApps() {
        String[] rootPkgs = {
            "com.topjohnwu.magisk",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.noshufou.android.su",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.kingroot.kinguser",
            "com.kingo.root",
            "com.zachspong.temprootremovejb",
            "com.ramdroid.appquarantine"
        };
        Context ctx = MagenApp.getInstance();
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : rootPkgs) {
            try { pm.getPackageInfo(pkg, 0); return true; }
            catch (PackageManager.NameNotFoundException ignored) {}
            catch (Exception ignored) {}
        }
        return false;
    }

    private static boolean checkRootPaths() {
        String[] paths = { "/system/app/Superuser", "/system/etc/init.d", "/dev/su" };
        for (String p : paths) {
            try { if (new File(p).exists()) return true; } catch (Exception ignored) {}
        }
        return false;
    }

    // ---------------- 2. חתימת APK ----------------

    public static boolean isSignatureTampered(Context ctx) {
        if (EXPECTED_SIG_SHA256 == null || EXPECTED_SIG_SHA256.trim().isEmpty()) {
            return false;   // לא הוגדרה חתימה צפויה — דלג
        }
        try {
            String actual = getOwnSignatureSha256(ctx);
            if (actual == null) return false;
            return !actual.equalsIgnoreCase(EXPECTED_SIG_SHA256.replace(":", "").trim());
        } catch (Exception e) {
            Log.e(TAG, "sig check error: " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public static String getOwnSignatureSha256(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            String pkg = ctx.getPackageName();
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                if (pi.signingInfo == null) return null;
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            if (sigs == null || sigs.length == 0) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "getOwnSignatureSha256: " + e.getMessage());
            return null;
        }
    }

    // ---------------- 3. שעון ----------------

    /** קורא פעם אחת בהתקנה/הפעלה ראשונה כדי לקבע קו בסיס. */
    public static void initClockBaseline(Context ctx) {
        try {
            MagenApp.getInstance().getPrefs().edit()
                .putLong(K_BASE_WALL, System.currentTimeMillis())
                .putLong(K_BASE_ELAPSED, SystemClock.elapsedRealtime())
                .apply();
        } catch (Exception ignored) {}
    }

    /**
     * זמן "אמין" משוער: קו הבסיס + כמה שהמונה המונוטוני התקדם.
     * לא מושפע משינוי ידני של שעון הקיר (אלא אם המכשיר אותחל).
     */
    public static long getTrustedTimeApprox(Context ctx) {
        try {
            long baseWall = MagenApp.getInstance().getPrefs().getLong(K_BASE_WALL, 0);
            long baseElapsed = MagenApp.getInstance().getPrefs().getLong(K_BASE_ELAPSED, 0);
            if (baseWall == 0) { initClockBaseline(ctx); return System.currentTimeMillis(); }
            long elapsedDelta = SystemClock.elapsedRealtime() - baseElapsed;
            if (elapsedDelta < 0) {   // המכשיר אותחל — קבע קו בסיס מחדש
                initClockBaseline(ctx);
                return System.currentTimeMillis();
            }
            return baseWall + elapsedDelta;
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    /** האם שעון הקיר סוטה משמעותית מהזמן ה"אמין"? (חשד למניפולציה) */
    public static boolean isClockTampered(Context ctx) {
        try {
            long trusted = getTrustedTimeApprox(ctx);
            long wall = System.currentTimeMillis();
            return Math.abs(wall - trusted) > CLOCK_TOLERANCE_MS;
        } catch (Exception e) {
            return false;
        }
    }
}
