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

        // 1.25 כללי allow/block ידניים מה-VPS מגיעים בתוך policy חתום.
        int serverRule = com.magen.family.server.ServerRuleCache.get(ctx, h);
        if (serverRule == com.magen.family.server.ServerRuleCache.ALLOW) return false;
        if (serverRule == com.magen.family.server.ServerRuleCache.BLOCK) return blockIncident(ctx,h,"SERVER_RULE","MANUAL_POLICY",1.0);

        // YouTube compatibility: generic tracker/adult lists may contain telemetry
        // hosts such as s.youtube.com. Blocking those breaks watch history and can
        // make the app look offline. Explicit parent/VPS BLOCK rules above still win.
        if (YouTubeEssentialHosts.isEssential(h)) return false;

        // 1.5 חוסמים ספקי DoH/DoT ידועים — אחרת אפשר לעקוף את סינון ה-DNS
        //     שלנו ע"י שליחת שאילתות מוצפנות ל-resolver חיצוני.
        if (DohResolvers.isDohHost(h)) return blockIncident(ctx,h,"DOH_BYPASS_GUARD","BYPASS_RESOLVER",1.0);

        // 2. רשימה קשיחה + סיומות (.xxx/.porn/...) — קטגוריית adult
        init(ctx);
        ContentFilter cf = contentFilter;
        if (cf != null && cf.isHostBlocked(h)) return blockIncident(ctx,h,"STATIC_ADULT_RULES","ADULT_EXPLICIT",1.0);

        // 3. קטגוריות נוספות שהמשתמש הדליק (הימורים/היכרויות/חברתי/קניות/אלימות)
        //    מעבירים את המארח המלא: CategoryLists יודע לחלץ את הליבה הרשומה
        //    נכון גם בסיומות דו-חלקיות (bet365.co.uk), מה ש-rootDomain כאן
        //    לא ידע — הוא היה מחזיר "co.uk".
        if (CategoryLists.isBlockedByCategory(ctx, h)) return blockIncident(ctx,h,"CATEGORY_POLICY","CATEGORY_BLOCK",1.0);

        // 4. Bloom filter מרוחק — כולל התאמת סאב-דומיינים
        try {
            if (RemoteBlocklist.isBlocked(h)) return blockIncident(ctx,h,"REMOTE_BLOCKLIST","ADULT_BLOCKLIST",1.0);
        } catch (Exception e) {
            Log.w(TAG, "remote list check failed: " + e.getMessage());
        }

        // 5. זיהוי mirror/proxy דינמי — אתרים חדשים שהרשימות עוד לא הכירו
        if (looksLikeAdultMirror(h)) return blockIncident(ctx,h,"MIRROR_HEURISTIC","ADULT_MIRROR",0.96);

        // 6. VPS Intelligence — רק אחרי שכל הידע המקומי מוצה. DeepSeek לעולם
        // לא נקרא מתוך thread ה-VPN: cache מקומי מוחזר מייד, וב-miss נשלחת
        // בדיקה אסינכרונית. strict_unknown חוסם זמנית עד שה-verdict החתום חוזר.
        try {
            int remote = com.magen.family.server.ServerVerdictCache.get(ctx, h);
            if (remote == com.magen.family.server.ServerVerdictCache.BLOCK) return blockIncident(ctx,h,"SERVER_VERDICT_CACHE","SERVER_CLASSIFIED",1.0);
            if (remote == com.magen.family.server.ServerVerdictCache.SAFE) return false;
            if (com.magen.family.server.ServerConfig.ready(ctx)) {
                if (remote == com.magen.family.server.ServerVerdictCache.NONE)
                    com.magen.family.server.RemoteIntelligenceClient.classifyAsync(ctx, h);
                return com.magen.family.server.ServerConfig.strictUnknown(ctx);
            }
        } catch (Exception e) {
            Log.w(TAG, "server intelligence check failed: " + e.getMessage());
        }

        return false;
    }

    private static boolean blockIncident(Context ctx,String host,String source,String category,double confidence) {
        try { com.magen.family.server.ContentIncidentReporter.reportDomainBlock(ctx,host,source,category,confidence); }
        catch (Exception ignored) {}
        return true;
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
        return HostUtil.normalizeHost(host);
    }

    /** נקרא אחרי עדכון רשימה כדי שההחלטות הישנות לא ישארו תקועות. */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }
}
