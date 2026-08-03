package com.magen.family.filter;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * SafeSearchEnforcer — כפיית Safe Search ו-YouTube Restricted ברמת ה-DNS.
 *
 * למה ברמת DNS ולא בדפדפן:
 *   מנועי החיפוש הגדולים מיישמים "safe search כפוי" דרך CNAME ייעודי:
 *   מי שמכריח את www.google.com להצביע ל-forcesafesearch.google.com — מקבל
 *   safe search נעול, שאי אפשר לכבות מתוך הדפדפן. זו השיטה שכל מסנני הרשת
 *   המקצועיים משתמשים בה. היא עובדת בכל דפדפן ואפליקציה, בלי לגעת ב-UI.
 *
 * איך זה עובד כאן:
 *   כשמגיעה שאילתת DNS למנוע חיפוש וההגדרה דלוקה, ה-VPN עונה עם ה-IP של
 *   גרסת ה-safe search במקום ה-IP הרגיל. כתובות ה-IP האלה יציבות ומתועדות
 *   ע"י הספקים.
 *
 * מקורות ה-IP (מתועדים רשמית):
 *   Google / YouTube Restricted → forcesafesearch.google.com → 216.239.38.120
 *   YouTube Moderate            → restrictmoderate.youtube.com → 216.239.38.119
 *   Bing                        → strict.bing.com → 204.79.197.220
 */
public final class SafeSearchEnforcer {

    private static final String PREFS = "magen_filter";
    private static final String KEY_SAFE_SEARCH = "enforce_safe_search";
    private static final String KEY_YT_RESTRICT = "enforce_youtube";

    // host (בלי www.) -> IPv4 של הגרסה הבטוחה
    private static final Map<String, byte[]> SEARCH_MAP = new HashMap<>();
    private static final Map<String, byte[]> YOUTUBE_MAP = new HashMap<>();

    private static final byte[] GOOGLE_SAFE   = ip(216, 239, 38, 120);
    private static final byte[] YT_STRICT     = ip(216, 239, 38, 120);
    private static final byte[] BING_STRICT   = ip(204, 79, 197, 220);

    static {
        // Google — כל הדומיינים הלאומיים הנפוצים
        for (String g : new String[]{
            "google.com", "google.co.il", "google.co.uk", "google.de", "google.fr",
            "google.es", "google.it", "google.ru", "google.com.br", "google.ca",
            "google.com.au", "google.co.in", "google.nl", "google.pl" }) {
            SEARCH_MAP.put(g, GOOGLE_SAFE);
            SEARCH_MAP.put("www." + g, GOOGLE_SAFE);
        }
        SEARCH_MAP.put("bing.com", BING_STRICT);
        SEARCH_MAP.put("www.bing.com", BING_STRICT);

        // YouTube
        for (String y : new String[]{
            "youtube.com", "www.youtube.com", "m.youtube.com",
            "youtubei.googleapis.com", "youtube.googleapis.com",
            "www.youtube-nocookie.com" }) {
            YOUTUBE_MAP.put(y, YT_STRICT);
        }
    }

    private SafeSearchEnforcer() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isSafeSearchOn(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SAFE_SEARCH, true);   // דלוק כברירת מחדל
    }

    public static boolean isYoutubeRestrictOn(Context ctx) {
        return prefs(ctx).getBoolean(KEY_YT_RESTRICT, true);
    }

    public static void setSafeSearch(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_SAFE_SEARCH, on).apply();
    }

    public static void setYoutubeRestrict(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_YT_RESTRICT, on).apply();
    }

    /**
     * מחזיר את ה-IP הכפוי עבור מארח, או null אם אין כפייה עליו.
     * נקרא מנתיב ה-DNS ב-VPN.
     */
    public static byte[] forcedIp(Context ctx, String host) {
        if (host == null) return null;
        String h = host.toLowerCase();
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);

        if (isSafeSearchOn(ctx)) {
            byte[] ip = SEARCH_MAP.get(h);
            if (ip != null) return ip;
        }
        if (isYoutubeRestrictOn(ctx)) {
            byte[] ip = YOUTUBE_MAP.get(h);
            if (ip != null) return ip;
        }
        return null;
    }

    private static byte[] ip(int a, int b, int c, int d) {
        return new byte[]{ (byte) a, (byte) b, (byte) c, (byte) d };
    }
}
