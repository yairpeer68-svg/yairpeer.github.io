package com.magen.family.covenant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * CovenantJournal — צ׳ק-אין יומי, יומן רפלקציה, ונוסח הברית.
 *
 * הכל מקומי (SharedPreferences, JSON) — בלי שרת ובלי DB. הכמויות קטנות
 * (רשומה קצרה ביום), ולכן זה מספיק ופשוט.
 *
 * שלושה חלקים:
 *   • pledge — נוסח הברית שהמשתמש חתם עליו, עם תאריך. מחזק מחויבות.
 *   • check-in — דיווח יומי קצר (מרגיש חזק / בסדר / מתקשה).
 *   • journal — רשומות רפלקציה חופשיות.
 */
public final class CovenantJournal {

    private static final String PREFS = "magen_covenant";
    private static final String K_PLEDGE = "pledge_text";
    private static final String K_PLEDGE_AT = "pledge_at";
    private static final String K_JOURNAL = "journal_entries";
    private static final String K_LAST_CHECKIN = "last_checkin_day";

    private static final int MAX_ENTRIES = 200;

    private CovenantJournal() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- נוסח הברית ----------------

    public static String getPledge(Context ctx) {
        return prefs(ctx).getString(K_PLEDGE, "");
    }

    public static long getPledgeDate(Context ctx) {
        return prefs(ctx).getLong(K_PLEDGE_AT, 0);
    }

    public static boolean hasPledge(Context ctx) {
        return !getPledge(ctx).isEmpty();
    }

    public static void signPledge(Context ctx, String text) {
        prefs(ctx).edit()
            .putString(K_PLEDGE, text)
            .putLong(K_PLEDGE_AT, System.currentTimeMillis())
            .apply();
    }

    // ---------------- צ׳ק-אין יומי ----------------

    /** האם כבר עשו צ׳ק-אין היום? */
    public static boolean didCheckInToday(Context ctx) {
        return todayKey().equals(prefs(ctx).getString(K_LAST_CHECKIN, ""));
    }

    public static void checkIn(Context ctx, String mood, String note) {
        prefs(ctx).edit().putString(K_LAST_CHECKIN, todayKey()).apply();
        addEntry(ctx, "צ׳ק-אין: " + mood + (note != null && !note.isEmpty() ? " — " + note : ""));
    }

    // ---------------- יומן ----------------

    public static void addEntry(Context ctx, String text) {
        if (text == null || text.trim().isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(K_JOURNAL, "[]"));
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("text", text.trim());
            arr.put(o);
            // גיזום — שומרים רק את האחרונות
            while (arr.length() > MAX_ENTRIES) arr.remove(0);
            prefs(ctx).edit().putString(K_JOURNAL, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static class Entry {
        public final long time;
        public final String text;
        Entry(long t, String s) { time = t; text = s; }
    }

    public static List<Entry> getEntries(Context ctx) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(K_JOURNAL, "[]"));
            for (int i = arr.length() - 1; i >= 0; i--) {   // חדש קודם
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.optLong("t"), o.optString("text")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static String formatDate(long t) {
        return new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(t));
    }

    private static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
