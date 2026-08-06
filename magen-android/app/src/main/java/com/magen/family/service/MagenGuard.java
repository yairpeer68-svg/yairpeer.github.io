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
    private static final String KEY_SETUP_GRACE = "setup_grace_until";
    private static final long WINDOW_MS = 5 * 60 * 1000L;   // 5 דקות
    // 5 דקות, ומתחדש בכל חזרה למדריך. 90 שנ' היה קצר מדי: בשלב הנגישות
    // המשתמש שוהה במסך ההגדרות זמן רב, וברגע שהפעיל את השירות ההגנה
    // הייתה חמושה וזורקת אותו הביתה — בדיוק כשהעניק את ההרשאה.
    private static final long SETUP_GRACE_MS = 5 * 60 * 1000L;

    private MagenGuard() {}

    /**
     * חלון חסד קצר בזמן המדריך — נפתח כשהמדריך שולח את המשתמש למסך הרשאה
     * של המערכת, כדי שההגנה העצמית לא תחסום את מתן ההרשאה עצמה.
     *
     * למה קצר ולא "עד סוף המדריך": קודם כל ההגנה הייתה מותנית בדגל
     * onboarding_done, ולכן מי שלא סיים את המדריך נשאר *בלי שום הגנה* —
     * אפשר היה לשנות כל הרשאה בלי אישור. עכשיו החלון נסגר מעצמו.
     */
    public static void grantSetupGrace(Context ctx) {
        prefs(ctx).edit()
            .putLong(KEY_SETUP_GRACE, System.currentTimeMillis() + SETUP_GRACE_MS)
            .apply();
    }

    /** סוגר מיד את חלון החסד — נקרא בסיום המדריך. */
    public static void endSetupGrace(Context ctx) {
        prefs(ctx).edit().remove(KEY_SETUP_GRACE).apply();
    }

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
            if (now < p.getLong(KEY_SETUP_GRACE, 0)) return true;
            // קוד חירום פעיל נחשב גם הוא כחלון תחזוקה
            if (p.getBoolean("emergency_mode", false)
                    && now < p.getLong("emergency_mode_until", 0)) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * האם ההגנה אמורה לפעול בכלל?
     *
     * הקריטריון הוא קיום קוד הברית — הרגע שבו המשתמש התחייב. *לא* סיום
     * המדריך: הגרסה הקודמת התנתה את ההגנה ב-onboarding_done, ולכן מי
     * שנתקע באמצע המדריך (למשל כשאנדרואיד חסם את מסך מנהל המכשיר) קיבל
     * אפליקציה עם כל ההרשאות דלוקות ובלי שום הגנה בפועל.
     */
    public static boolean isArmed(Context ctx) {
        try {
            String pin = ctx.getSharedPreferences(MagenApp.PREFS_NAME, Context.MODE_PRIVATE)
                            .getString(MagenApp.KEY_PIN, "");
            return pin != null && !pin.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(MagenApp.PREFS_NAME, Context.MODE_PRIVATE);
    }
}
