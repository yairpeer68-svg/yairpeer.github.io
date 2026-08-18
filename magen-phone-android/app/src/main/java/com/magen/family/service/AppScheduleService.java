package com.magen.family.service;

import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.magen.family.MagenApp;
import com.magen.family.model.AppSchedule;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

/**
 * AppScheduleService — מנהל לוחות זמנים פר-אפליקציה.
 *
 * שמירה: JSON ב-SharedPreferences תחת "app_schedules".
 * שאילתה: AccessibilityService קורא ל-isAppBlockedNow(pkg) ברגע שאפליקציה עולה.
 */
public class AppScheduleService extends Service {

    private static final String TAG = "AppSchedule";
    private static final String PREFS_KEY = "app_schedules";

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // השירות לא צריך לרוץ ברציפות — הוא רק container ל-static helpers
        return START_NOT_STICKY;
    }

    // ===== API פומבי =====

    public static void saveSchedule(Context ctx, AppSchedule schedule) {
        try {
            Map<String, AppSchedule> all = loadAll(ctx);
            all.put(schedule.packageName, schedule);

            JSONArray arr = new JSONArray();
            for (AppSchedule s : all.values()) arr.put(s.toJson());

            MagenApp.getInstance().getPrefs().edit()
                .putString(PREFS_KEY, arr.toString())
                .apply();
            invalidateScheduleCache();
        } catch (Exception e) {
            Log.e(TAG, "saveSchedule: " + e.getMessage());
        }
    }

    public static void removeSchedule(Context ctx, String packageName) {
        try {
            Map<String, AppSchedule> all = loadAll(ctx);
            all.remove(packageName);
            JSONArray arr = new JSONArray();
            for (AppSchedule s : all.values()) arr.put(s.toJson());
            MagenApp.getInstance().getPrefs().edit()
                .putString(PREFS_KEY, arr.toString()).apply();
            invalidateScheduleCache();
        } catch (Exception e) {
            Log.e(TAG, "removeSchedule: " + e.getMessage());
        }
    }

    public static Map<String, AppSchedule> loadAll(Context ctx) {
        Map<String, AppSchedule> out = new HashMap<>();
        String raw = MagenApp.getInstance().getPrefs().getString(PREFS_KEY, "");
        if (raw.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                AppSchedule s = AppSchedule.fromJson(arr.getJSONObject(i));
                out.put(s.packageName, s);
            }
        } catch (Exception e) {
            Log.e(TAG, "loadAll: " + e.getMessage());
        }
        return out;
    }

    public static AppSchedule getSchedule(Context ctx, String packageName) {
        return loadAll(ctx).get(packageName);
    }

    // ===== cache =====
    // הקריאה החמה רצה ב-thread של שירות הנגישות על כל החלפת אפליקציה.
    // בלי cache היא עשתה שם פרסור JSON מלא *וגם* queryAndAggregateUsageStats
    // (קריאת IPC יקרה שסורקת יום שלם) — עשרות עד מאות מילישניות, בדיוק ברגע
    // שהמשתמש מחליף מסך. ההערה בקוד אמרה "cache" אבל לא היה כזה.

    private static volatile Map<String, AppSchedule> scheduleCache = null;
    private static volatile long scheduleCacheAt = 0;
    private static final long SCHEDULE_CACHE_MS = 30_000L;

    private static final Map<String, long[]> usageCache = new HashMap<>();
    private static final long USAGE_CACHE_MS = 60_000L;

    /** נקרא אחרי כל שינוי בלוחות הזמנים. */
    private static void invalidateScheduleCache() {
        scheduleCache = null;
    }

    private static Map<String, AppSchedule> cachedSchedules(Context ctx) {
        long now = System.currentTimeMillis();
        Map<String, AppSchedule> cached = scheduleCache;
        if (cached != null && now - scheduleCacheAt < SCHEDULE_CACHE_MS) return cached;

        Map<String, AppSchedule> fresh = loadAll(ctx);
        scheduleCache = fresh;
        scheduleCacheAt = now;
        return fresh;
    }

    /**
     * הקריאה החמה — נקראת בכל פעם שאפליקציה עולה לחזית.
     * הודות ל-cache היא מחזירה מיד עבור הרוב המכריע של האפליקציות
     * (אלה שאין להן לוח זמנים כלל).
     */
    public static boolean isAppBlockedNow(Context ctx, String packageName) {
        Map<String, AppSchedule> all = cachedSchedules(ctx);
        if (all.isEmpty()) return false;

        AppSchedule s = all.get(packageName);
        if (s == null) return false;

        long usedMinutes = cachedUsageMinutes(ctx, packageName);
        boolean allowed = s.isAllowedNow(usedMinutes);
        if (!allowed) {
            Log.d(TAG, "App " + packageName + " blocked by schedule (used=" + usedMinutes + ")");
        }
        return !allowed;
    }

    /** זמן שימוש עם cache של דקה — הנתון משתנה לאט ממילא. */
    private static long cachedUsageMinutes(Context ctx, String packageName) {
        long now = System.currentTimeMillis();
        synchronized (usageCache) {
            long[] entry = usageCache.get(packageName);
            if (entry != null && now - entry[1] < USAGE_CACHE_MS) return entry[0];
        }

        long minutes = getTodayUsageMinutes(ctx, packageName);
        synchronized (usageCache) {
            usageCache.put(packageName, new long[]{ minutes, now });
        }
        return minutes;
    }

    /**
     * זמן השימוש של אפליקציה מסוימת היום (בדקות).
     * משתמש ב-UsageStatsManager — דורש הרשאת PACKAGE_USAGE_STATS שההורה מאשר.
     */
    public static long getTodayUsageMinutes(Context ctx, String packageName) {
        UsageStatsManager usm = (UsageStatsManager)
            ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return 0;

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        Map<String, UsageStats> stats = usm.queryAndAggregateUsageStats(
            cal.getTimeInMillis(), System.currentTimeMillis());
        if (stats == null) return 0;

        UsageStats us = stats.get(packageName);
        if (us == null) return 0;
        return us.getTotalTimeInForeground() / 60_000L;
    }
}
