package com.magen.family.service;

import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import com.magen.family.MagenApp;
import java.util.Calendar;
import java.util.Map;

public class ScreenTimeService extends Service {

    private static final String TAG = "ScreenTime";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable checker;
    /** מונע הפעלה חוזרת של הנעילה בכל דקה אחרי חציית המכסה. */
    private boolean limitHit = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startChecking();
        return START_STICKY;
    }

    private void startChecking() {
        if (checker != null) return;
        checker = new Runnable() {
            @Override
            public void run() {
                checkScreenTime();
                if (checker == this) handler.postDelayed(this, 60_000); // בדוק כל דקה
            }
        };
        handler.post(checker);
    }

    @Override
    public void onDestroy() {
        if (checker != null) {
            handler.removeCallbacks(checker);
            checker = null;
        }
        super.onDestroy();
    }

    private void checkScreenTime() {
        MagenApp app = MagenApp.getInstance();
        if (app == null) return;

        boolean enabled = app.getPrefs().getBoolean("screen_time_enabled", false);
        if (!enabled) return;

        int maxMinutes = app.getPrefs().getInt("screen_time_max_minutes", 120);

        // חשב זמן מסך של היום
        long usedMinutes = getTodayScreenTimeMinutes();
        Log.d(TAG, "Used: " + usedMinutes + " / Max: " + maxMinutes);

        // שמור לסטטיסטיקות
        app.getPrefs().edit().putLong("screen_time_today_minutes", usedMinutes).apply();

        if (usedMinutes >= maxMinutes) {
            // רק בפעם הראשונה שהמכסה נחצתה — לא כל דקה מחדש
            if (!limitHit) {
                limitHit = true;
                Log.d(TAG, "🚫 Screen time exceeded");
                Intent ks = new Intent(this, MagenKillSwitch.class);
                ks.putExtra("require_pin", true);
                MagenKillSwitch.start(this, ks);
            }
        } else {
            limitHit = false;
        }
    }

    /**
     * זמן מסך של היום.
     *
     * הבאג שתוקן: הגרסה הקודמת סכמה getTotalTimeInForeground על *כל* החבילות,
     * כולל המשגר, ה-System UI ואפליקציות מערכת. ההערה בקוד אמרה "אל תספור
     * אפליקציות מערכת" אבל שום סינון כזה לא בוצע. בנוסף חלונות זמן של
     * אפליקציות שונות חופפים, ולכן הסכום הגולמי גדול בהרבה מזמן המסך האמיתי
     * — מגבלה של שעתיים נחצתה כבר בבוקר.
     *
     * עכשיו: מסננים חבילות מערכת ואת המשגר, ומגבילים כל חבילה לחלון היום.
     */
    private long getTodayScreenTimeMinutes() {
        UsageStatsManager usm = (UsageStatsManager)
            getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return 0;

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long startOfDay = cal.getTimeInMillis();
        long now = System.currentTimeMillis();
        long dayWindowMs = now - startOfDay;

        Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(startOfDay, now);
        if (stats == null) return 0;

        PackageManager pm = getPackageManager();
        String launcher = resolveLauncherPackage(pm);

        long totalMs = 0;
        for (Map.Entry<String, UsageStats> entry : stats.entrySet()) {
            String pkg = entry.getKey();
            long fg = entry.getValue().getTotalTimeInForeground();
            if (fg <= 0) continue;

            if (pkg.equals(getPackageName())) continue;
            if (pkg.equals(launcher)) continue;
            if (isSystemPackage(pm, pkg)) continue;

            // חלון היום הוא התקרה לכל חבילה — מגן מפני ערכים מושחתים
            totalMs += Math.min(fg, dayWindowMs);
        }

        return totalMs / 60_000;
    }

    private String resolveLauncherPackage(PackageManager pm) {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
            return info != null && info.activityInfo != null
                ? info.activityInfo.packageName : "";
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isSystemPackage(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            // אפליקציות מערכת שעודכנו נחשבות "רגילות" ולכן נספרות
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            return system && !updated;
        } catch (Exception e) {
            return true;   // לא מוכר — לא סופרים
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
