package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;

import com.magen.family.MagenApp;

/**
 * MagenGuard — חלון תחזוקה קצר.
 *
 * למה זה קיים:
 *   ההגנה העצמית חוסמת ניסיונות לבטל הרשאות של האפליקציה (נגישות, VPN,
 *   הצגה-מעל, סוללה, מנהל-מכשיר) ומסך "מידע על האפליקציה". בלי מנגנון
 *   הפוגה, גם *הבעלים* — שיודע את קוד הברית — היה ננעל בחוץ ולא היה יכול
 *   לשנות הגדרות או להעניק מחדש הרשאה שהמערכת הפילה.
 *
 *   הפתרון: ברגע שמזינים נכון את קוד הברית, נפתח חלון תחזוקה בן 5 דקות
 *   שבו ההגנה העצמית מושהית. מי שלא יודע את הקוד לא יכול לפתוח אותו,
 *   ולכן ההגנה נשארת חזקה — אבל הבעלים לא ננעל בחוץ.
 *
 *   קוד החירום (15 דק') מכובד גם הוא כחלון תחזוקה.
 */
public final class MagenGuard {

    private static final String KEY_UNTIL = "maintenance_until";
    private static final long WINDOW_MS = 5 * 60 * 1000L;   // 5 דקות

    private MagenGuard() {}

    /** נקרא אחרי אימות מוצלח של קוד הברית. */
    public static void grantMaintenance(Context ctx) {
        prefs(ctx).edit()
            .putLong(KEY_UNTIL, System.currentTimeMillis() + WINDOW_MS)
            .apply();
    }

    /** לסגור מוקדם את חלון התחזוקה (אופציונלי). */
    public static void endMaintenance(Context ctx) {
        prefs(ctx).edit().remove(KEY_UNTIL).apply();
    }

    /** האם ההגנה העצמית צריכה להיות מושהית כרגע? */
    public static boolean inMaintenance(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            long now = System.currentTimeMillis();
            if (now < p.getLong(KEY_UNTIL, 0)) return true;
            // קוד חירום פעיל נחשב גם הוא כחלון תחזוקה
            if (p.getBoolean("emergency_mode", false)
                    && now < p.getLong("emergency_mode_until", 0)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(MagenApp.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
