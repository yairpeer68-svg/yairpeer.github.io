package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Local cache of encouragement messages signed and delivered by the paired Magen VPS.
 *
 * v4 keeps the context of each message so the UI can choose an appropriate sentence:
 * BLOCKED / PANIC / DAILY / MILESTONE / GENERAL.
 */
public final class FallSentences {
    public static final String TYPE_GENERAL = "GENERAL";
    public static final String TYPE_BLOCKED = "BLOCKED";
    public static final String TYPE_PANIC = "PANIC";
    public static final String TYPE_DAILY = "DAILY";
    public static final String TYPE_MILESTONE = "MILESTONE";

    private static final String PREFS = "magen_chizuk";
    private static final String KEY_SERVER_ITEMS = "server_items_v4";
    private static final String KEY_SERVER_LEGACY = "server_sentences";
    private static final int MAX_SENTENCES = 500;
    private static final int MAX_WEIGHT = 20;
    private static final Random RND = new Random();

    private static final String[] DEFAULT_BLOCKED = {
        "עצור רגע. נשום. אתה חזק מהרגע הזה.",
        "הרצף שלך יקר מדי מכדי לאבד אותו עכשיו.",
        "מי שאתה רוצה להיות מחר מתחיל בבחירה של עכשיו."
    };
    private static final String[] DEFAULT_PANIC = {
        "הפיתוי חולף. תן לעצמך עוד דקה לפני כל החלטה.",
        "נשימה: שאף 4 · החזק 4 · נשוף 6."
    };
    private static final String[] DEFAULT_GENERAL = {
        "תזכור למה התחלת. הבחירה של עכשיו חשובה.",
        "רגע אחד של עצירה שווה יותר מכל מה שמחכה מעבר."
    };

    private FallSentences() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getRandom(Context ctx) {
        return getForContext(ctx, TYPE_GENERAL);
    }

    /** Returns a weighted random sentence for the requested context, then GENERAL, then local fallback. */
    public static String getForContext(Context ctx, String contextType) {
        String wanted = normalizeType(contextType);
        List<WeightedSentence> exact = new ArrayList<>();
        List<WeightedSentence> general = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString(KEY_SERVER_ITEMS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String text = o.optString("text", "").trim();
                if (text.isEmpty() || text.length() > 300) continue;
                String type = normalizeType(o.optString("type", TYPE_GENERAL));
                int weight = Math.max(1, Math.min(o.optInt("weight", 1), MAX_WEIGHT));
                WeightedSentence ws = new WeightedSentence(text, weight);
                if (type.equals(wanted)) exact.add(ws);
                else if (type.equals(TYPE_GENERAL)) general.add(ws);
            }
        } catch (Exception ignored) {}
        if (!exact.isEmpty()) return choose(exact);
        if (!general.isEmpty()) return choose(general);

        // v3 compatibility cache, used only when a v4 structured cache is not available.
        List<String> legacy = getLegacy(ctx);
        if (!legacy.isEmpty()) return legacy.get(RND.nextInt(legacy.size()));

        String[] fallback = TYPE_BLOCKED.equals(wanted) ? DEFAULT_BLOCKED
            : TYPE_PANIC.equals(wanted) ? DEFAULT_PANIC
            : DEFAULT_GENERAL;
        return fallback[RND.nextInt(fallback.length)];
    }

    /** Structured v4 payload. The server response is already signature-verified by MagenApiClient. */
    public static void replaceStructuredFromServer(Context ctx, JSONArray items) {
        JSONArray out = new JSONArray();
        int n = 0;
        if (items != null) {
            for (int i = 0; i < items.length() && n < MAX_SENTENCES; i++) {
                JSONObject in = items.optJSONObject(i);
                if (in == null) continue;
                String text = in.optString("text", "").trim();
                if (text.isEmpty() || text.length() > 300) continue;
                String type = normalizeType(in.optString("type", TYPE_GENERAL));
                int weight = Math.max(1, Math.min(in.optInt("weight", 1), MAX_WEIGHT));
                try {
                    JSONObject o = new JSONObject();
                    o.put("text", text);
                    o.put("type", type);
                    o.put("weight", weight);
                    out.put(o);
                    n++;
                } catch (Exception ignored) {}
            }
        }
        prefs(ctx).edit().putString(KEY_SERVER_ITEMS, out.toString()).apply();
    }

    /** Legacy v3 payload support. */
    public static void replaceFromServer(Context ctx, List<String> sentences) {
        JSONArray a = new JSONArray();
        int n = 0;
        if (sentences != null) for (String s : sentences) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty() || t.length() > 300) continue;
            a.put(t);
            if (++n >= 200) break;
        }
        prefs(ctx).edit().putString(KEY_SERVER_LEGACY, a.toString()).apply();
    }

    public static int count(Context ctx) {
        try { return new JSONArray(prefs(ctx).getString(KEY_SERVER_ITEMS, "[]")).length(); }
        catch (Exception ignored) { return getLegacy(ctx).size(); }
    }

    private static List<String> getLegacy(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(ctx).getString(KEY_SERVER_LEGACY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty() && s.length() <= 300) out.add(s);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String normalizeType(String type) {
        String t = type == null ? TYPE_GENERAL : type.trim().toUpperCase(java.util.Locale.US);
        if (TYPE_BLOCKED.equals(t) || TYPE_PANIC.equals(t) || TYPE_DAILY.equals(t)
                || TYPE_MILESTONE.equals(t) || TYPE_GENERAL.equals(t)) return t;
        return TYPE_GENERAL;
    }

    private static String choose(List<WeightedSentence> list) {
        int total = 0;
        for (WeightedSentence w : list) total += w.weight;
        int pick = RND.nextInt(Math.max(1, total));
        for (WeightedSentence w : list) {
            pick -= w.weight;
            if (pick < 0) return w.text;
        }
        return list.get(0).text;
    }

    private static final class WeightedSentence {
        final String text;
        final int weight;
        WeightedSentence(String text, int weight) { this.text = text; this.weight = weight; }
    }
}
