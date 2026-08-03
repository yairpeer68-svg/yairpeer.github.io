package com.magen.family.i18n;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

/**
 * LocaleManager — בחירת שפת האפליקציה (עברית / אנגלית) ללא תלות בשפת המכשיר.
 *
 * למה לא להסתמך על שפת המערכת:
 *   חלק מהמשתמשים מחזיקים מכשיר באנגלית ורוצים את האפליקציה בעברית, או להפך.
 *   כאן השפה נשמרת מקומית ומוחלת על כל Activity דרך attachBaseContext.
 *
 * ברירת המחדל היא עברית — זו שפת הקהל העיקרי, וגם שפת ברירת המחדל של
 * res/values (values-en הוא ההרחבה האנגלית).
 */
public final class LocaleManager {

    private static final String PREFS = "magen_locale";
    private static final String KEY_LANG = "app_lang";

    public static final String HE = "he";
    public static final String EN = "en";

    private LocaleManager() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** השפה השמורה, או עברית כברירת מחדל. */
    public static String getLanguage(Context ctx) {
        return prefs(ctx).getString(KEY_LANG, HE);
    }

    public static void setLanguage(Context ctx, String lang) {
        prefs(ctx).edit().putString(KEY_LANG, lang).apply();
    }

    public static boolean isHebrew(Context ctx) {
        return HE.equals(getLanguage(ctx));
    }

    /**
     * עוטף Context בשפה הנבחרת. נקרא מ-attachBaseContext של כל Activity
     * ומ-MagenApp, כך שכל השאילתות ל-getString מחזירות את השפה הנכונה.
     */
    public static Context wrap(Context ctx) {
        String lang = getLanguage(ctx);
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);

        Configuration config = new Configuration(ctx.getResources().getConfiguration());
        config.setLocale(locale);
        // כיווניות (RTL לעברית) נגזרת אוטומטית מה-Locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLayoutDirection(locale);
            return ctx.createConfigurationContext(config);
        } else {
            config.locale = locale;
            ctx.getResources().updateConfiguration(config,
                ctx.getResources().getDisplayMetrics());
            return ctx;
        }
    }
}
