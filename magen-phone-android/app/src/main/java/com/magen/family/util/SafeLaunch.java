package com.magen.family.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * SafeLaunch — פתיחת מסכי הגדרות מערכת בלי להפיל את האפליקציה.
 *
 * למה זה קיים:
 *   לא כל מסך הגדרות קיים בכל ROM. יצרנים מסירים, מחליפים או מגבילים מסכים
 *   (במיוחד "גישה לנתוני שימוש", "מנהלי מכשיר", "הצג מעל אפליקציות").
 *   startActivity לכזה מסך זורק ActivityNotFoundException — וזה קרס בפועל
 *   אצל משתמש בשלב מנהל המכשיר במדריך ההתקנה.
 *
 * למה *לא* בודקים resolveActivity מראש:
 *   מאנדרואיד 11 חל סינון נראות חבילות; resolveActivity עלול להחזיר null
 *   גם כשהפתיחה הייתה מצליחה. בדיקה מקדימה כזו מנעה פתיחה של מסכים
 *   שעבדו מצוין. לכן: מנסים לפתוח, ורק אם נכשל — עוברים לגיבוי.
 */
public final class SafeLaunch {

    private static final String TAG = "SafeLaunch";

    private SafeLaunch() {}

    /**
     * מנסה לפתוח את ה-intent; אם נכשל מנסה את הגיבויים לפי הסדר; אם הכל
     * נכשל — פותח את מסך פרטי האפליקציה ומציג הודעה.
     *
     * @return true אם נפתח מסך כלשהו
     */
    public static boolean open(Context ctx, Intent primary, String... fallbackActions) {
        if (tryStart(ctx, primary)) return true;

        for (String action : fallbackActions) {
            Intent i = new Intent(action);
            if (tryStart(ctx, i)) return true;
        }

        // גיבוי אחרון — מסך פרטי האפליקציה, שקיים תמיד
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + ctx.getPackageName()));
        if (tryStart(ctx, details)) return true;

        try {
            Toast.makeText(ctx, "לא ניתן לפתוח את מסך ההגדרות במכשיר הזה",
                Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
        return false;
    }

    /** קיצור לפתיחת מסך הגדרות לפי action בלבד. */
    public static boolean openAction(Context ctx, String action, String... fallbackActions) {
        return open(ctx, new Intent(action), fallbackActions);
    }

    private static boolean tryStart(Context ctx, Intent i) {
        if (ctx == null || i == null) return false;
        try {
            // Activity context should keep the system settings screen in the same task.
            // NEW_TASK is only required when launching from Service/Receiver/Application.
            if (!(ctx instanceof Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot open " + i.getAction() + ": " + e.getClass().getSimpleName());
            return false;
        }
    }
}
