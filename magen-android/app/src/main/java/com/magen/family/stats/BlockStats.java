package com.magen.family.stats;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;

/**
 * BlockStats — תיעוד חסימות לפי שעה ויום, לצורך dashboard וניתוח דפוסים.
 *
 * הכל מקומי (SharedPreferences), בלי שרת ובלי DB. שני מבנים קטנים:
 *   • היסטוגרמה של 24 שעות — כמה חסימות בכל שעה ביממה (מצטבר).
 *   • 7 ימים אחרונים — מונה מתגלגל לפי יום בשבוע.
 *
 * מזה נגזרים התובנות: "רוב הפיתויים בין 23:00 ל-01:00" ומפת החום השבועית.
 */
public final class BlockStats {

    private static final String PREFS = "magen_stats";
    private static final String K_HOURLY = "hist_hourly";      // 24 ערכים מופרדים בפסיק
    private static final String K_DOW = "hist_dow";            // 7 ערכים (יום בשבוע)
    private static final String K_PEAK_STREAK_MILESTONE = "streak_milestone";

    private BlockStats() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** מתעד חסימה אחת בשעה/יום הנוכחיים. נקרא מ-incrementBlockedCount. */
    public static void record(Context ctx) {
        try {
            Calendar c = Calendar.getInstance();
            int hour = c.get(Calendar.HOUR_OF_DAY);       // 0-23
            int dow  = c.get(Calendar.DAY_OF_WEEK) - 1;   // 0-6

            int[] hourly = getArray(ctx, K_HOURLY, 24);
            hourly[hour]++;
            putArray(ctx, K_HOURLY, hourly);

            int[] dowArr = getArray(ctx, K_DOW, 7);
            dowArr[dow]++;
            putArray(ctx, K_DOW, dowArr);
        } catch (Exception ignored) {}
    }

    public static int[] getHourly(Context ctx) { return getArray(ctx, K_HOURLY, 24); }
    public static int[] getByDayOfWeek(Context ctx) { return getArray(ctx, K_DOW, 7); }

    /** שעת השיא (עם הכי הרבה חסימות), או -1 אם אין נתונים. */
    public static int peakHour(Context ctx) {
        int[] h = getHourly(ctx);
        int max = 0, idx = -1;
        for (int i = 0; i < 24; i++) if (h[i] > max) { max = h[i]; idx = i; }
        return idx;
    }

    public static int total(Context ctx) {
        int[] h = getHourly(ctx);
        int t = 0;
        for (int v : h) t += v;
        return t;
    }

    /**
     * תובנה מילולית על דפוס הזמן. למשל:
     * "רוב החסימות מתרכזות בין 23:00 ל-01:00 — שווה לשים לב לשעה הזו."
     */
    public static String timeInsight(Context ctx) {
        int peak = peakHour(ctx);
        if (peak < 0 || total(ctx) < 5) return null;
        int next = (peak + 1) % 24;
        return String.format("רוב החסימות מתרכזות סביב %02d:00–%02d:00 — שווה לשים לב לשעה הזו.",
            peak, next);
    }

    // ---------------- הישגי רצף ----------------

    /** מחזיר אבן דרך חדשה שהושגה (1/7/30/90/180/365) או 0 אם אין חדשה. */
    public static int newStreakMilestone(Context ctx, int streakDays) {
        int[] milestones = { 365, 180, 90, 30, 14, 7, 3, 1 };
        int reached = 0;
        for (int m : milestones) { if (streakDays >= m) { reached = m; break; } }
        int last = prefs(ctx).getInt(K_PEAK_STREAK_MILESTONE, 0);
        if (reached > last) {
            prefs(ctx).edit().putInt(K_PEAK_STREAK_MILESTONE, reached).apply();
            return reached;
        }
        return 0;
    }

    public static void resetMilestone(Context ctx) {
        prefs(ctx).edit().putInt(K_PEAK_STREAK_MILESTONE, 0).apply();
    }

    // ---------------- עזר ----------------

    private static int[] getArray(Context ctx, String key, int size) {
        int[] out = new int[size];
        String raw = prefs(ctx).getString(key, "");
        if (raw.isEmpty()) return out;
        String[] parts = raw.split(",");
        for (int i = 0; i < size && i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i]); } catch (Exception ignored) {}
        }
        return out;
    }

    private static void putArray(Context ctx, String key, int[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        prefs(ctx).edit().putString(key, sb.toString()).apply();
    }
}
