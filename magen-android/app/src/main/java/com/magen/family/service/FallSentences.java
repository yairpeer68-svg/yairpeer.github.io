package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * FallSentences — מאגר "משפטי חיזוק" אישיים.
 *
 * הרעיון (בקשת המשתמש):
 *   המשתמש כותב מראש משפטים אישיים ("רגע לפני שאתה נופל, תזכור ש..."), והם
 *   מסונכרנים מצ'אט הטלגרם שלו. ברגע שהמערכת מזהה שהוא עומד ליפול — כלומר
 *   בכל חסימת תוכן — קופץ לו אחד מהמשפטים האלה. זה הופך את רגע החסימה
 *   מ"קיר" ל"קול פנימי" שהוא בעצמו כתב בשעה שקטה.
 *
 * אחסון:
 *   רשימת המשפטים נשמרת מקומית (JSON) יחד עם offset של getUpdates כדי
 *   לא לשאוב את אותה הודעה פעמיים. אם המאגר ריק — נופלים לברירות מחדל,
 *   כך שהתכונה עובדת גם לפני הסנכרון הראשון.
 */
public final class FallSentences {

    private static final String PREFS = "magen_chizuk";
    private static final String KEY_LIST   = "sentences";        // אישי (מהבוט)
    private static final String KEY_GLOBAL = "global_sentences"; // משותף (מהערוץ הציבורי)
    private static final String KEY_OFFSET = "tg_offset";
    private static final int MAX_SENTENCES = 300;

    private static final Random RND = new Random();

    /** ברירות מחדל — עובדות מיד, עוד לפני שהמשתמש כתב משהו משלו. */
    private static final String[] DEFAULTS = {
        "עצור רגע. נשום. אתה חזק מהרגע הזה.",
        "הרצף שלך יקר מדי מכדי לאבד אותו עכשיו.",
        "מי שאתה רוצה להיות מחר מתחיל בבחירה של עכשיו.",
        "הפיתוי חולף. הגאווה על ההתגברות נשארת.",
        "אתה לא לבד בזה. תזכור למה התחלת את הברית.",
        "רגע אחד של עצירה שווה יותר מכל מה שמחכה מעבר.",
        "נשימה: שאף 4 · החזק 4 · נשוף 6. וזהו — עבר."
    };

    private FallSentences() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * משפט אקראי להצגה ברגע החסימה. ממזג את המשפטים המשותפים (מהערוץ) עם
     * האישיים (מהבוט). אם שניהם ריקים — נופל לברירות מחדל. לעולם לא ריק.
     */
    public static String getRandom(Context ctx) {
        List<String> pool = new ArrayList<>(getAll(ctx));
        for (String g : getAllGlobal(ctx)) if (!pool.contains(g)) pool.add(g);
        if (pool.isEmpty()) return DEFAULTS[RND.nextInt(DEFAULTS.length)];
        return pool.get(RND.nextInt(pool.size()));
    }

    /** המשפטים האישיים (מהבוט של המשתמש). */
    public static List<String> getAll(Context ctx) {
        return readList(ctx, KEY_LIST);
    }

    /** המשפטים המשותפים (מהערוץ הציבורי שכולם קוראים). */
    public static List<String> getAllGlobal(Context ctx) {
        return readList(ctx, KEY_GLOBAL);
    }

    private static List<String> readList(Context ctx, String key) {
        List<String> out = new ArrayList<>();
        try {
            String raw = prefs(ctx).getString(key, "");
            if (raw.isEmpty()) return out;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static int count(Context ctx) { return getAll(ctx).size(); }

    public static int countGlobal(Context ctx) { return getAllGlobal(ctx).size(); }

    /** מחליף את מאגר המשפטים המשותפים (הערוץ הוא מקור-האמת). */
    public static void replaceGlobal(Context ctx, List<String> sentences) {
        List<String> clean = new ArrayList<>();
        if (sentences != null) {
            for (String s : sentences) {
                if (s == null) continue;
                String t = s.trim();
                if (!t.isEmpty() && !clean.contains(t)) clean.add(t);
            }
        }
        while (clean.size() > MAX_SENTENCES) clean.remove(0);
        saveTo(ctx, KEY_GLOBAL, clean);
    }

    /** מוסיף משפט אחד (מדלג על ריק/כפילות, שומר על תקרה). */
    public static void add(Context ctx, String sentence) {
        List<String> one = new ArrayList<>();
        one.add(sentence);
        addAll(ctx, one);
    }

    /** מוסיף אצווה, שומר על ייחודיות וסדר, וחותך לתקרה. */
    public static void addAll(Context ctx, List<String> sentences) {
        if (sentences == null || sentences.isEmpty()) return;
        List<String> pool = getAll(ctx);
        for (String s : sentences) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty() || pool.contains(t)) continue;
            pool.add(t);
        }
        // אם עברנו את התקרה — משאירים את החדשים ביותר
        while (pool.size() > MAX_SENTENCES) pool.remove(0);
        saveTo(ctx, KEY_LIST, pool);
    }

    public static void replaceAll(Context ctx, List<String> sentences) {
        List<String> clean = new ArrayList<>();
        if (sentences != null) {
            for (String s : sentences) {
                if (s == null) continue;
                String t = s.trim();
                if (!t.isEmpty() && !clean.contains(t)) clean.add(t);
            }
        }
        while (clean.size() > MAX_SENTENCES) clean.remove(0);
        saveTo(ctx, KEY_LIST, clean);
    }

    /** מוחק רק את המאגר האישי (המשותף נשאר — הוא מגיע מהערוץ). */
    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY_LIST).apply();
    }

    private static void saveTo(Context ctx, String key, List<String> pool) {
        JSONArray arr = new JSONArray();
        for (String s : pool) arr.put(s);
        prefs(ctx).edit().putString(key, arr.toString()).apply();
    }

    // ---- offset של getUpdates (כדי לא לשאוב הודעה פעמיים) ----

    public static long getOffset(Context ctx) {
        return prefs(ctx).getLong(KEY_OFFSET, 0);
    }

    public static void setOffset(Context ctx, long offset) {
        prefs(ctx).edit().putLong(KEY_OFFSET, offset).apply();
    }
}
