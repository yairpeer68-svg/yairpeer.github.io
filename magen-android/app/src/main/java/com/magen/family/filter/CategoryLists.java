package com.magen.family.filter;

import android.content.Context;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * CategoryLists — דומיינים מקובצים לפי קטגוריה, לחסימה מותנית-הגדרה.
 *
 * הקטגוריה adult מכוסה כבר ע"י ContentFilter ו-RemoteBlocklist; כאן מרוכזות
 * הקטגוריות הנוספות שהמשתמש יכול להדליק בנפרד.
 *
 * שתי בעיות שתוקנו כאן:
 *   1. קטגוריית "אלימות" הוגדרה ב-FilterPolicy אבל *לא הייתה לה רשימה כלל* —
 *      הדלקתה לא חסמה שום דבר.
 *   2. ההתאמה הייתה על דומיין-שורש מדויק, ולכן וריאנטים אזוריים חמקו:
 *      bet365.co.uk, facebook.co.il, amazon.de. עכשיו משווים את *ליבת* השם
 *      (החלק לפני הסיומת), כך שכל הווריאנטים נתפסים ברשומה אחת.
 */
public final class CategoryLists {

    /** ליבות שם (בלי סיומת) — תופס גם וריאנטים אזוריים. */
    private static final Set<String> GAMBLING = new HashSet<>(Arrays.asList(
        "bet365", "888casino", "888poker", "888sport", "pokerstars", "betway",
        "williamhill", "ladbrokes", "bwin", "unibet", "partypoker", "draftkings",
        "fanduel", "betfair", "stake", "roobet", "winner", "1xbet", "22bet",
        "melbet", "parimatch", "betsson", "casumo", "leovegas", "mrgreen",
        "betano", "pinnacle", "sportingbet", "tipico", "rollbit", "duelbits",
        "csgoempire", "gamdom", "lottomatica", "gambling", "casino", "poker",
        "roulette", "blackjack", "slots", "betfred", "coral", "paddypower"
    ));

    private static final Set<String> DATING = new HashSet<>(Arrays.asList(
        "tinder", "bumble", "okcupid", "match", "pof", "badoo", "grindr",
        "hinge", "adultfriendfinder", "ashleymadison", "meetic", "jdate",
        "zoosk", "eharmony", "happn", "hily", "tantan", "coffeemeetsbagel",
        "plentyoffish", "lovoo", "jaumo", "wapa", "scruff", "hornet", "feeld",
        "seeking", "sugardaddy", "flirt", "cupid", "elitesingles"
    ));

    private static final Set<String> SOCIAL = new HashSet<>(Arrays.asList(
        "facebook", "instagram", "tiktok", "snapchat", "twitter",
        "reddit", "tumblr", "9gag", "discord", "twitch", "onlyfans", "fansly",
        "pinterest", "vk", "weibo", "douyin", "kuaishou", "likee", "bigo",
        "omegle", "chatroulette", "chatous", "meetme", "yubo", "houseparty",
        "clubhouse", "threads", "bluesky", "mastodon", "truthsocial", "gettr",
        "parler", "rumble", "kick", "bereal", "lemon8", "imgur", "4chan", "8kun"
    ));

    private static final Set<String> SHOPPING = new HashSet<>(Arrays.asList(
        "amazon", "ebay", "aliexpress", "wish", "shein", "temu", "asos",
        "etsy", "alibaba", "walmart", "target", "bestbuy", "zalando", "boohoo",
        "farfetch", "wayfair", "overstock", "banggood", "gearbest",
        "lightinthebox", "ozon", "rakuten", "mercadolibre", "flipkart",
        "myntra", "ajio"
    ));

    /**
     * אלימות ותוכן קיצוני. הקטגוריה קיימת ב-FilterPolicy מאז ומעולם לא
     * הייתה לה רשימה — כלומר הדלקתה לא חסמה דבר.
     */
    private static final Set<String> VIOLENCE = new HashSet<>(Arrays.asList(
        "liveleak", "bestgore", "documentingreality", "theync", "kaotic",
        "goregrish", "seegore", "watchpeopledie", "hoodsite", "crazyshit",
        "deathaddict", "gorecenter", "shockgore", "efukt", "rotten",
        "gore", "beheading", "execution", "snuff", "torture"
    ));

    private CategoryLists() {}

    /**
     * סיומות דו-חלקיות. בלעדיהן "bet365.co.uk" היה מזוהה כדומיין-שורש
     * "co.uk" והליבה הייתה "co" — כלומר כל וריאנט אזורי היה חומק.
     */
    private static final Set<String> TWO_PART_TLDS = new HashSet<>(Arrays.asList(
        "co.uk", "org.uk", "net.uk", "ac.uk", "gov.uk",
        "co.il", "org.il", "net.il", "ac.il",
        "com.au", "net.au", "org.au", "com.br", "com.mx", "com.ar",
        "co.jp", "co.kr", "co.nz", "co.za", "com.tr", "com.cn",
        "com.tw", "com.hk", "com.sg", "com.my", "com.ph", "com.pl",
        "co.in", "com.ua", "com.ru", "co.th", "com.vn"
    ));

    /** האם המארח חסום לפי אחת הקטגוריות שהמשתמש הדליק? */
    public static boolean isBlockedByCategory(Context ctx, String host) {
        if (host == null || host.isEmpty()) return false;
        String core = core(host);
        if (core.isEmpty()) return false;

        if (FilterPolicy.isCategoryOn(ctx, FilterPolicy.CAT_GAMBLING)
                && GAMBLING.contains(core)) return true;
        if (FilterPolicy.isCategoryOn(ctx, FilterPolicy.CAT_DATING)
                && DATING.contains(core)) return true;
        if (FilterPolicy.isCategoryOn(ctx, FilterPolicy.CAT_SOCIAL)
                && SOCIAL.contains(core)) return true;
        if (FilterPolicy.isCategoryOn(ctx, FilterPolicy.CAT_SHOPPING)
                && SHOPPING.contains(core)) return true;
        if (FilterPolicy.isCategoryOn(ctx, FilterPolicy.CAT_VIOLENCE)
                && VIOLENCE.contains(core)) return true;

        return false;
    }

    /**
     * ליבת השם הרשום — התווית שלפני הסיומת, כולל סיומות דו-חלקיות:
     *   www.facebook.com  -> facebook
     *   m.bet365.co.uk    -> bet365
     *   news.example.co.il -> example
     * כך וריאנט אזורי או סאב-דומיין לא דורשים רשומה נפרדת.
     */
    static String core(String host) {
        String h = host.trim().toLowerCase();
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        String[] p = h.split("\\.");
        if (p.length < 2) return h;

        // סיומת דו-חלקית? אז הליבה נמצאת שלוש תוויות מהסוף.
        String lastTwo = p[p.length - 2] + "." + p[p.length - 1];
        if (TWO_PART_TLDS.contains(lastTwo)) {
            return p.length >= 3 ? p[p.length - 3] : "";
        }
        return p[p.length - 2];
    }
}
