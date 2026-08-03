package com.magen.family.covenant;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * StreakManager — מונה "ימים נקיים", הלב הפסיכולוגי של מודל הברית.
 *
 * למה זה עובד:
 *   המנוע ההתנהגותי החזק ביותר בכלי אחריות אינו החסימה — אלא הרצף. הידיעה
 *   ש"אני על 12 ימים נקיים" יוצרת מחויבות לא לשבור אותו. זה בדיוק מה
 *   ש-Covenant Eyes ותוכניות התאוששות בונות עליו.
 *
 * מה נחשב "שבירת רצף":
 *   *לא* חסימת תוכן — חסימה היא המסנן שעובד, כלומר הצלחה. הרצף נשבר רק על
 *   אירוע אמיתי של כניעה/עקיפה:
 *     • דיווח עצמי ("החלקתי היום")
 *     • ניסיון עקיפה אמיתי (VPN חיצוני / ניסיון הסרה)
 *   כך הרצף מודד את מה שבאמת חשוב, לא את כמות הפיתויים שנחסמו.
 */
public final class StreakManager {

    private static final String PREFS = "magen_covenant";
    private static final String K_START   = "streak_start";      // תחילת הרצף הנוכחי
    private static final String K_LONGEST = "streak_longest";     // השיא (בימים)
    private static final String K_LAST_SLIP = "last_slip_at";
    private static final String K_TOTAL_SLIPS = "total_slips";

    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private StreakManager() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** מאתחל את תחילת הרצף אם עוד לא הוגדר (התקנה ראשונה). */
    public static void ensureStarted(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (p.getLong(K_START, 0) == 0) {
            p.edit().putLong(K_START, System.currentTimeMillis()).apply();
        }
    }

    /** מספר הימים ברצף הנוכחי. */
    public static int currentDays(Context ctx) {
        long start = prefs(ctx).getLong(K_START, System.currentTimeMillis());
        long diff = System.currentTimeMillis() - start;
        return diff < 0 ? 0 : (int) (diff / DAY_MS);
    }

    public static int longestDays(Context ctx) {
        return Math.max(prefs(ctx).getInt(K_LONGEST, 0), currentDays(ctx));
    }

    public static int totalSlips(Context ctx) {
        return prefs(ctx).getInt(K_TOTAL_SLIPS, 0);
    }

    public static long lastSlipAt(Context ctx) {
        return prefs(ctx).getLong(K_LAST_SLIP, 0);
    }

    /**
     * שבירת רצף. שומר את השיא, מאפס את הרצף, ומתריע לשותף האחריות —
     * זה בדיוק הרגע שבו שיחה עוזרת.
     */
    public static void recordSlip(Context ctx, String reason) {
        SharedPreferences p = prefs(ctx);
        int current = currentDays(ctx);
        int longest = p.getInt(K_LONGEST, 0);

        p.edit()
            .putInt(K_LONGEST, Math.max(longest, current))
            .putLong(K_START, System.currentTimeMillis())
            .putLong(K_LAST_SLIP, System.currentTimeMillis())
            .putInt(K_TOTAL_SLIPS, p.getInt(K_TOTAL_SLIPS, 0) + 1)
            .apply();

        try {
            com.magen.family.service.NotificationHelper.notifyPartnerUrgent(ctx,
                "💔 הרצף נשבר אחרי " + current + " ימים"
                + (reason != null && !reason.isEmpty() ? " (" + reason + ")" : "")
                + ". זה הרגע לשיחה — לא לשיפוט.");
        } catch (Exception ignored) {}
    }

    /** דיווח עצמי — מהמסך של מרכז הברית. */
    public static void selfReportSlip(Context ctx) {
        recordSlip(ctx, "דיווח עצמי");
    }
}
