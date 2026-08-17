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

        // 3. קטגוריות נוספות שהמשתמש הדליק (הימורים/היכרויות/חברתי/קניות/אלימות)
        //    מעבירים את המארח המלא: CategoryLists יודע לחלץ את הליבה הרשומה
        //    נכון גם בסיומות דו-חלקיות (bet365.co.uk), מה ש-rootDomain כאן
        //    לא ידע — הוא היה מחזיר "co.uk".
        if (CategoryLists.isBlockedByCategory(ctx, h)) return true;

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
     *
     * הקשחה נגד עקיפה: mirrors רבים מסתירים את המילה בעזרת leetspeak (p0rnhub,
     * pr0n, s3xcam) או מפרידים אותה במקפים/קווים תחתונים (porn-hub, x_videos).
     * לכן משווים לא רק מול ה-core הגולמי אלא גם מול גרסה מנורמלת שמסירה מפרידים
     * ומחזירה ספרות-leet לאותיות. package-private כדי שבדיקת יחידה תכסה את זה.
     */
    static boolean looksLikeAdultMirror(String host) {
        if (host == null) return false;
        String core = host;
        int firstDot = core.indexOf('.');
        if (firstDot > 0) core = core.substring(0, firstDot);   // רק החלק לפני ה-TLD

        String deleeted = deLeet(core);   // p0rn-hub -> pornhub

        // מותגים שידוע שיש להם עשרות mirrors
        String[] brands = { "pornhub", "xvideos", "xnxx", "xhamster", "redtube",
            "youporn", "spankbang", "brazzers", "onlyfans", "chaturbate" };
        for (String b : brands) {
            if (core.contains(b) || deleeted.contains(b)) return true;
        }

        // מילות תוכן מפורשות בתוך שם הדומיין (לא בנתיב)
        String[] tokens = { "porn", "xxx", "hentai", "sexcam", "camsex", "escort" };
        for (String t : tokens) {
            if (core.contains(t) || deleeted.contains(t)) return true;
        }
        return false;
    }

    /**
     * מנרמל מחרוזת לצורך ההיוריסטיקה: מסיר כל תו שאינו אות/ספרה (מקפים,
     * קווים תחתונים), וממפה ספרות leetspeak נפוצות חזרה לאותיות. כך
     * "p0rn-hub", "x_videos" ו-"s3xcam" מתלכדים לצורה המילולית.
     *
     * הערה: זו היוריסטיקה סלחנית שמעדיפה over-block על miss — בהתאם לפילוסופיית
     * האפליקציה ("טוב מספיק למי שלא באמת מנסה לעקוף"). לכן מיפוי 1->i ולא 1->l,
     * וללא ניסיון לפענח כל וריאציה אפשרית.
     */
    static String deLeet(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            switch (c) {
                case '0': sb.append('o'); break;
                case '1': sb.append('i'); break;
                case '3': sb.append('e'); break;
                case '4': sb.append('a'); break;
                case '5': sb.append('s'); break;
                case '7': sb.append('t'); break;
                case '@': sb.append('a'); break;
                case '$': sb.append('s'); break;
                default:
                    if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
                    // כל השאר (מקף, קו תחתון, נקודה) — נבלע
            }
        }
        return sb.toString();
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
