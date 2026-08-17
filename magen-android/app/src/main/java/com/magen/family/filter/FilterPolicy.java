package com.magen.family.filter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * FilterPolicy — רמת הסינון והקטגוריות הפעילות.
 *
 * רמות:
 *   LIGHT  — חסימת דומיינים מפורשים בלבד (רשימות). כמעט אפס חסימות שווא.
 *   MEDIUM — דומיינים + מילות מפתח עם גבולות מילה + כפיית safe search. (ברירת מחדל)
 *   STRICT — כמו MEDIUM + חסימה על כל חשד, גם על מילה בודדת בהקשר עמום.
 *
 * קטגוריות — כל אחת נשלטת בנפרד:
 *   adult (ברירת מחדל דלוק), gambling, dating, social, violence, shopping.
 */
public final class FilterPolicy {

    private static final String PREFS = "magen_filter";

    private static final String K_LEVEL = "filter_level";

    public static final int LIGHT = 0, MEDIUM = 1, STRICT = 2;

    // קטגוריות
    public static final String CAT_ADULT    = "cat_adult";
    public static final String CAT_GAMBLING = "cat_gambling";
    public static final String CAT_DATING   = "cat_dating";
    public static final String CAT_SOCIAL   = "cat_social";
    public static final String CAT_VIOLENCE = "cat_violence";
    public static final String CAT_SHOPPING = "cat_shopping";

    private FilterPolicy() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getLevel(Context ctx) {
        return prefs(ctx).getInt(K_LEVEL, MEDIUM);
    }

    public static void setLevel(Context ctx, int level) {
        prefs(ctx).edit().putInt(K_LEVEL, level).apply();
    }

    public static boolean isCategoryOn(Context ctx, String cat) {
        // adult דלוק כברירת מחדל; השאר תלוי-קטגוריה
        boolean def = CAT_ADULT.equals(cat) || CAT_GAMBLING.equals(cat) || CAT_DATING.equals(cat);
        return prefs(ctx).getBoolean(cat, def);
    }

    public static void setCategory(Context ctx, String cat, boolean on) {
        prefs(ctx).edit().putBoolean(cat, on).apply();
    }

    /** האם להשתמש בהתאמת מילות מפתח (מעבר לרשימת דומיינים)? */
    public static boolean useKeywords(Context ctx) {
        return getLevel(ctx) >= MEDIUM;
    }

    /** האם לחסום גם על חשד עמום (מילה בודדת בהקשר)? */
    public static boolean aggressive(Context ctx) {
        return getLevel(ctx) >= STRICT;
    }
}
