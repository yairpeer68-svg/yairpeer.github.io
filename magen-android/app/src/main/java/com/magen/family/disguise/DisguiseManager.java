package com.magen.family.disguise;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.Log;

import com.magen.family.R;

import java.util.ArrayList;
import java.util.List;

/**
 * DisguiseManager — הסוואת האפליקציה: 20 אפשרויות איך היא תיראה במסך הבית.
 *
 * למה זה קיים:
 *   אפליקציית סינון/אחריות שמותקנת מרצון עדיין נראית לעין כל במגירת האפליקציות
 *   עם השם "שומר הברית". מי שמתקין על עצמו לפעמים רוצה שזה לא יבלוט — שהאייקון
 *   ייראה כמו מחשבון או פנקס. זו לא הסתרה מהמשתמש עצמו (הוא יודע שזה שם), אלא
 *   דיסקרטיות מול מי שמסתכל על הטלפון מהצד.
 *
 * איך זה עובד טכנית:
 *   כל "מסכה" היא activity-alias ב-Manifest שמצביע על WelcomeActivity, עם אייקון
 *   ושם משלה. בכל רגע רק alias אחד מופעל (enabled) והוא זה שמופיע במגירה.
 *   מעבר = מפעילים את החדש ומכבים את הישן דרך PackageManager.
 *
 * הערות חשובות:
 *   • תמיד חייב להישאר בדיוק alias אחד מופעל, אחרת האפליקציה נעלמת מהמגירה
 *     ואי אפשר לפתוח אותה. לכן מפעילים־חדש לפני שמכבים־ישן.
 *   • חלק מהמשגרים דורשים רגע/הפעלה מחדש כדי לרענן את האייקון. זה תלוי-יצרן.
 *   • השם המוצג לא משפיע על android:label של האפליקציה בהגדרות — רק על המגירה.
 */
public final class DisguiseManager {

    private static final String TAG = "DisguiseManager";
    private static final String PKG = "com.magen.family";

    public static class Disguise {
        public final String aliasName;   // שם ה-component המלא
        public final int labelRes;       // שם מוצג
        public final int iconRes;        // אייקון
        public final int colorRes;       // צבע רקע לתצוגה במסך הבחירה

        Disguise(String alias, int label, int icon, int color) {
            this.aliasName = PKG + alias;
            this.labelRes = label;
            this.iconRes = icon;
            this.colorRes = color;
        }
    }

    /**
     * 20 המסכות. הראשונה (המגן) היא ברירת המחדל וחייבת להישאר קיימת.
     * הסדר כאן תואם את סדר ה-activity-alias ב-Manifest.
     */
    public static final List<Disguise> ALL = new ArrayList<>();
    static {
        ALL.add(new Disguise(".disguise.AliasShield",     R.string.disg_shield,     R.drawable.disg_shield,     R.color.accent));
        ALL.add(new Disguise(".disguise.AliasCalculator", R.string.disg_calculator, R.drawable.disg_calculator, R.color.disg_slate));
        ALL.add(new Disguise(".disguise.AliasNotes",      R.string.disg_notes,      R.drawable.disg_notes,      R.color.disg_amber));
        ALL.add(new Disguise(".disguise.AliasWeather",    R.string.disg_weather,    R.drawable.disg_weather,    R.color.disg_sky));
        ALL.add(new Disguise(".disguise.AliasClock",      R.string.disg_clock,      R.drawable.disg_clock,      R.color.disg_indigo));
        ALL.add(new Disguise(".disguise.AliasCalendar",   R.string.disg_calendar,   R.drawable.disg_calendar,   R.color.disg_red));
        ALL.add(new Disguise(".disguise.AliasFiles",      R.string.disg_files,      R.drawable.disg_files,      R.color.disg_teal));
        ALL.add(new Disguise(".disguise.AliasGallery",    R.string.disg_gallery,    R.drawable.disg_gallery,    R.color.disg_pink));
        ALL.add(new Disguise(".disguise.AliasMusic",      R.string.disg_music,      R.drawable.disg_music,      R.color.disg_purple));
        ALL.add(new Disguise(".disguise.AliasRadio",      R.string.disg_radio,      R.drawable.disg_radio,      R.color.disg_orange));
        ALL.add(new Disguise(".disguise.AliasFlashlight", R.string.disg_flashlight, R.drawable.disg_flashlight, R.color.disg_dark));
        ALL.add(new Disguise(".disguise.AliasCompass",    R.string.disg_compass,    R.drawable.disg_compass,    R.color.disg_green));
        ALL.add(new Disguise(".disguise.AliasConverter",  R.string.disg_converter,  R.drawable.disg_converter,  R.color.disg_cyan));
        ALL.add(new Disguise(".disguise.AliasDictionary", R.string.disg_dictionary, R.drawable.disg_dictionary, R.color.disg_brown));
        ALL.add(new Disguise(".disguise.AliasTasks",      R.string.disg_tasks,      R.drawable.disg_tasks,      R.color.disg_lime));
        ALL.add(new Disguise(".disguise.AliasScanner",    R.string.disg_scanner,    R.drawable.disg_scanner,    R.color.disg_blue));
        ALL.add(new Disguise(".disguise.AliasBattery",    R.string.disg_battery,    R.drawable.disg_battery,    R.color.disg_green));
        ALL.add(new Disguise(".disguise.AliasWifi",       R.string.disg_wifi,       R.drawable.disg_wifi,       R.color.disg_sky));
        ALL.add(new Disguise(".disguise.AliasRecorder",   R.string.disg_recorder,   R.drawable.disg_recorder,   R.color.disg_red));
        ALL.add(new Disguise(".disguise.AliasTimer",      R.string.disg_timer,      R.drawable.disg_timer,      R.color.disg_indigo));
    }

    private DisguiseManager() {}

    /** המסכה שמופעלת כרגע (זו שיש לה component enabled). */
    public static int currentIndex(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (int i = 0; i < ALL.size(); i++) {
            int state = pm.getComponentEnabledSetting(
                new ComponentName(PKG, ALL.get(i).aliasName));
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i;
        }
        // אף אחד לא הוגדר במפורש → ברירת המחדל היא הראשון (enabled ב-Manifest)
        return 0;
    }

    /**
     * מחליף מסכה. מפעיל את היעד ואז מכבה את כל השאר, כדי שבשום רגע לא יישאר
     * אפס aliases מופעלים (מצב שבו האפליקציה נעלמת מהמגירה).
     */
    public static void apply(Context ctx, int index) {
        if (index < 0 || index >= ALL.size()) return;
        PackageManager pm = ctx.getPackageManager();

        try {
            // 1. הפעל את היעד
            pm.setComponentEnabledSetting(
                new ComponentName(PKG, ALL.get(index).aliasName),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

            // 2. כבה את כל השאר
            for (int i = 0; i < ALL.size(); i++) {
                if (i == index) continue;
                pm.setComponentEnabledSetting(
                    new ComponentName(PKG, ALL.get(i).aliasName),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            }
            Log.d(TAG, "disguise applied: " + index);
        } catch (Exception e) {
            Log.e(TAG, "apply failed: " + e.getMessage());
        }
    }
}
