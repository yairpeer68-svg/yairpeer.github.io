package com.magen.family.service;

import android.content.Context;

import com.magen.family.MagenApp;

import java.util.ArrayList;
import java.util.List;

/**
 * UpdateService — בגרסה הזו אין עדכון מהאינטרנט.
 *
 * נשארת רק התשתית של "blacklist מותאם אישית" שנשמרת מקומית ב-SharedPreferences.
 * אם תרצי להוסיף מילים חסומות מעבר ל-banned_words.xml, אפשר לעשות זאת ידנית
 * דרך הקוד או דרך מסך הגדרות (אם תוסיפי כזה).
 */
public class UpdateService {

    public UpdateService(Context ctx) {
        // no-op
    }

    public void checkAll() {
        // no-op — אין עדכונים חיצוניים
    }

    /**
     * מילים מותאמות אישית שנשמרו מקומית (שורה לכל מילה).
     */
    public static List<String> getCustomBlacklist() {
        String raw = MagenApp.getInstance().getPrefs()
            .getString("custom_blacklist", "");
        List<String> words = new ArrayList<>();
        if (raw.isEmpty()) return words;
        for (String w : raw.split("\n")) {
            w = w.trim();
            if (!w.isEmpty()) words.add(w);
        }
        return words;
    }

    /**
     * הוסף מילים לרשימה השחורה המקומית.
     */
    public static void addCustomWords(List<String> words) {
        List<String> existing = getCustomBlacklist();
        for (String w : words) {
            String t = w == null ? "" : w.trim();
            if (!t.isEmpty() && !existing.contains(t)) existing.add(t);
        }
        StringBuilder sb = new StringBuilder();
        for (String w : existing) sb.append(w).append("\n");
        MagenApp.getInstance().getPrefs().edit()
            .putString("custom_blacklist", sb.toString())
            .apply();
    }
}
