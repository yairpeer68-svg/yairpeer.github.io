package com.magen.family.filter;

import android.content.Context;
import android.util.Log;

import com.magen.family.service.RemoteBlocklist;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DomainVerdict — נקודת ההחלטה היחידה: "האם לחסום את המארח הזה?"
 *
 * למה זה קיים:
 *   קודם הייתה החלטה מפוזרת בשלושה מקומות שלא הסכימו ביניהם —
 *   ContentFilter.shouldBlock (רשימה קשיחה בקוד), רשימת banned_words,
 *   ו-RemoteBlocklist שכלל *לא נקרא מאף מקום* (הורדנו 3 מיליון דומיינים
 *   ולא השתמשנו בהם).
 *
 *   עכשיו כל שכבות הסינון — DNS ב-VPN, סינון SNI, ובדיקת URL בשירות
 *   הנגישות — עוברות דרך המתודה הזו. רשימה אחת, החלטה אחת, cache אחד.
 *
 * סדר הבדיקות (מהזול ליקר):
 *   1. cache
 *   2. whitelist מקומי (הורה יכול לפתוח דומיין ספציפי)
 *   3. רשימה קשיחה + סיומות למבוגרים (ContentFilter)
 *   4. Bloom filter מרוחק (UT1 + StevenBlack) — ~3M דומיינים
 */
public final class DomainVerdict {

    private static final String TAG = "DomainVerdict";
    private static final int CACHE_SIZE = 512;

    /** LRU cache — מארח -> חסום?  DNS/SNI נשאלים על אותם מארחים שוב ושוב. */
    private static final Map<String, Boolean> CACHE =
        new LinkedHashMap<String, Boolean>(CACHE_SIZE, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                return size() > CACHE_SIZE;
            }
        };

    private static volatile ContentFilter contentFilter;

    private DomainVerdict() {}

    /** מאתחל פעם אחת. בטוח לקריאה חוזרת. */
    public static void init(Context ctx) {
        if (contentFilter == null) {
            synchronized (DomainVerdict.class) {
                if (contentFilter == null) {
                    contentFilter = new ContentFilter(ctx.getApplicationContext());
                }
            }
        }
    }

    /**
     * ההכרעה. מקבל שם מארח נקי (בלי סכימה, בלי נתיב, בלי פורט).
     * בטוח לקריאה מכל thread, כולל ה-thread של ה-VPN.
     */
    public static boolean isBlocked(Context ctx, String host) {
        if (host == null) return false;

        String h = normalize(host);
        if (h.isEmpty()) return false;

        Boolean cached;
        synchronized (CACHE) {
            cached = CACHE.get(h);
        }
        if (cached != null) return cached;

        boolean blocked = evaluate(ctx, h);

        synchronized (CACHE) {
            CACHE.put(h, blocked);
        }
        if (blocked) Log.d(TAG, "BLOCK " + h);
        return blocked;
    }

    private static boolean evaluate(Context ctx, String h) {
        // 1. whitelist של ההורה גובר על הכל
        if (HostAllowList.isAllowed(ctx, h)) return false;

        // 1.5 חוסמים ספקי DoH/DoT ידועים — אחרת אפשר לעקוף את סינון ה-DNS
        //     שלנו ע"י שליחת שאילתות מוצפנות ל-resolver חיצוני.
        if (DohResolvers.isDohHost(h)) return true;

        // 2. רשימה קשיחה + סיומות (.xxx/.porn/...) — קטגוריית adult
        init(ctx);
        ContentFilter cf = contentFilter;
        if (cf != null && cf.isHostBlocked(h)) return true;

        // 3. קטגוריות נוספות שהמשתמש הדליק (הימורים/היכרויות/חברתי/קניות)
        if (CategoryLists.isBlockedByCategory(ctx, rootDomain(h))) return true;

        // 4. Bloom filter מרוחק — כולל התאמת סאב-דומיינים
        try {
            if (RemoteBlocklist.isBlocked(h)) return true;
        } catch (Exception e) {
            Log.w(TAG, "remote list check failed: " + e.getMessage());
        }

        // 5. זיהוי mirror/proxy דינמי — אתרים חדשים שהרשימות עוד לא הכירו
        if (looksLikeAdultMirror(h)) return true;

        return false;
    }

    /**
     * זיהוי היוריסטי של אתרי mirror/proxy לתוכן מבוגרים.
     *
     * למה צריך: אתרי פורנו פותחים דומיינים חדשים כל הזמן (mirrors) כדי לעקוף
     * חסימות. הרשימות מתעדכנות באיחור. ההיוריסטיקה תופסת דפוסים נפוצים —
     * שם מותג ידוע עם תוספת (xnxx2, pornhub-proxy), או צירוף של מילת-תוכן
     * מפורשת בשם הדומיין. גבולות מילה לא חלים כאן כי בשם דומיין אין רווחים.
     */
    private static boolean looksLikeAdultMirror(String host) {
        String core = host;
        int firstDot = core.indexOf('.');
        if (firstDot > 0) core = core.substring(0, firstDot);   // רק החלק לפני ה-TLD

        // מותגים שידוע שיש להם עשרות mirrors
        String[] brands = { "pornhub", "xvideos", "xnxx", "xhamster", "redtube",
            "youporn", "spankbang", "brazzers", "onlyfans", "chaturbate" };
        for (String b : brands) {
            if (core.contains(b)) return true;   // xnxx2, pornhubx, my-xvideos...
        }

        // מילות תוכן מפורשות בתוך שם הדומיין (לא בנתיב)
        String[] tokens = { "porn", "xxx", "hentai", "sexcam", "camsex", "escort" };
        for (String t : tokens) {
            if (core.contains(t)) return true;
        }
        return false;
    }

    /** דומיין-שורש: sub.a.example.com -> example.com */
    private static String rootDomain(String host) {
        String[] p = host.split("\\.");
        if (p.length < 2) return host;
        return p[p.length - 2] + "." + p[p.length - 1];
    }

    /** ניקוי שם מארח: lowercase, בלי www., בלי נקודה סופית, בלי פורט. */
    public static String normalize(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase();
        int colon = h.indexOf(':');
        if (colon > 0) h = h.substring(0, colon);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.startsWith("www.")) h = h.substring(4);
        return h;
    }

    /** נקרא אחרי עדכון רשימה כדי שההחלטות הישנות לא ישארו תקועות. */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
