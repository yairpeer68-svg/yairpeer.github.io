package com.magen.family.service;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.magen.family.MagenApp;
import com.magen.family.covenant.StreakManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * AccountabilityReporter — הלב של מודל ה"ברית".
 *
 * שולח דוח תקופתי לשותף האחריות/הורה עם תמונת מצב:
 *   • כמה חסימות תוכן היו (היום/השבוע)
 *   • כמה ניסיונות עקיפת VPN
 *   • כמה ניסיונות כניסה להגדרות/הסרה
 *   • התראות אבטחה (ADB/root/שעון) אם היו
 *
 * הפסיכולוגיה של "מישהו רואה" חזקה יותר מכל חסימה טכנית — וזה בדיוק מה
 * שמשתמש שמתקין את זה על עצמו מרצון צריך. הדוח נשלח דרך NotificationHelper
 * (שאפשר לחבר למייל/SMS/שרת לפי ההגדרה שלך).
 *
 * מתוזמן כ-JobService יומי. להוסיף ל-Manifest (ראה WIRING.md).
 */
public class AccountabilityReporter extends JobService {

    private static final String TAG = "AccountReporter";
    private static final int JOB_ID = 7714;
    private static final long INTERVAL_MS = 24 * 60 * 60 * 1000L;   // כל 24 שעות

    private static final String K_LAST_REPORT   = "last_report_time";
    private static final String K_SEC_ALERTS     = "security_alert_count";

    // ---------------- תזמון ----------------

    public static void schedule(Context ctx) {
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            ComponentName cn = new ComponentName(ctx, AccountabilityReporter.class);
            // בלי דרישת רשת: הדוח נשלח כהתראה מקומית ו/או SMS, ושניהם לא
            // צריכים אינטרנט. NETWORK_TYPE_ANY רק היה מעכב אותו ללא סיבה.
            JobInfo job = new JobInfo.Builder(JOB_ID, cn)
                .setPeriodic(INTERVAL_MS)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build();
            js.schedule(job);
            Log.d(TAG, "Daily accountability report scheduled");
        } catch (Exception e) {
            Log.e(TAG, "schedule failed: " + e.getMessage());
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            sendReport(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "report failed: " + e.getMessage());
        }
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }

    // ---------------- בניית ושליחת הדוח ----------------

    public static void sendReport(Context ctx) {
        SharedPreferences p = MagenApp.getInstance().getPrefs();

        int blockedToday = p.getInt(MagenApp.KEY_BLOCKED_TODAY, 0);
        int blockedWeek  = p.getInt(MagenApp.KEY_BLOCKED_WEEK, 0);
        int vpnAttempts  = p.getInt(MagenApp.KEY_VPN_ATTEMPTS, 0);
        int setAttempts  = p.getInt(MagenApp.KEY_SETTINGS_ATTEMPTS, 0);
        int secAlerts    = p.getInt(K_SEC_ALERTS, 0);

        String date = new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("he"))
            .format(new Date());

        int streak = StreakManager.currentDays(ctx);
        int longest = StreakManager.longestDays(ctx);

        StringBuilder sb = new StringBuilder();
        sb.append("📋 דוח שומר הברית — ").append(date).append("\n\n");
        sb.append("🔥 רצף נקי: ").append(streak).append(" ימים (שיא: ").append(longest).append(")\n");
        sb.append("🚫 חסימות היום: ").append(blockedToday).append("\n");
        sb.append("📊 חסימות השבוע: ").append(blockedWeek).append("\n");
        sb.append("🔓 ניסיונות עקיפת VPN: ").append(vpnAttempts).append("\n");
        sb.append("⚙️ ניסיונות כניסה להגדרות/הסרה: ").append(setAttempts).append("\n");
        if (secAlerts > 0) {
            sb.append("🚨 התראות אבטחה: ").append(secAlerts).append("\n");
        }

        // הערכת מצב קצרה
        sb.append("\n");
        if (vpnAttempts == 0 && setAttempts == 0 && secAlerts == 0) {
            sb.append("✅ שבוע נקי — לא זוהו ניסיונות עקיפה.");
        } else if (secAlerts > 0 || vpnAttempts > 5 || setAttempts > 5) {
            sb.append("⚠️ זוהו ניסיונות עקיפה חוזרים — כדאי לשוחח.");
        } else {
            sb.append("🔵 מספר ניסיונות בודדים — שווה תשומת לב.");
        }

        NotificationHelper.notifyPartnerDigest(ctx, sb.toString());
        Log.d(TAG, "Report sent to partner");

        // איפוס כל המונים התקופתיים אחרי הדוח.
        // קודם VPN_ATTEMPTS ו-SETTINGS_ATTEMPTS *לא* אופסו, ולכן הם צברו לנצח:
        // אחרי 5 ניסיונות אי-פעם, הדוח נתקע לצמיתות על "זוהו ניסיונות עקיפה
        // חוזרים" גם אחרי חודשים נקיים לגמרי.
        p.edit()
            .putInt(MagenApp.KEY_BLOCKED_TODAY, 0)
            .putInt(MagenApp.KEY_VPN_ATTEMPTS, 0)
            .putInt(MagenApp.KEY_SETTINGS_ATTEMPTS, 0)
            .putInt(K_SEC_ALERTS, 0)
            .putLong(K_LAST_REPORT, System.currentTimeMillis())
            .apply();
    }

    /** קריאה מ-SecurityGuard/IntegrityGuard כדי לספור התראות אבטחה לדוח. */
    public static void recordSecurityAlert(Context ctx) {
        try {
            SharedPreferences p = MagenApp.getInstance().getPrefs();
            p.edit().putInt(K_SEC_ALERTS, p.getInt(K_SEC_ALERTS, 0) + 1).apply();
        } catch (Exception ignored) {}
    }
}
