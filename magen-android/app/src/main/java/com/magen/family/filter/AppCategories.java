package com.magen.family.filter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AppCategories — סיווג אפליקציות מותקנות לקטגוריות.
 *
 * למה זה קיים:
 *   רשימת האפליקציות לחסימה הייתה רשימה אחת ארוכה בסדר אלפביתי — קשה
 *   למצוא בה מה שרוצים ובלתי אפשרי לחסום "את כל הרשתות החברתיות" בפעולה
 *   אחת. הסיווג כאן מאפשר סינון לפי קטגוריה וחסימה קבוצתית.
 *
 * איך מסווגים בלי שרת:
 *   PackageManager חושף קטגוריה רשמית רק מאנדרואיד 8 ומעלה, ורק אם המפתח
 *   הצהיר עליה — בפועל רוב האפליקציות מחזירות "לא ידוע". לכן מסווגים לפי
 *   שם החבילה: רשימת חבילות ידועות (מדויק), ואם אין התאמה — לפי מילות
 *   מפתח בשם החבילה (רחב). מה שלא זוהה נופל ל"אחר", ותמיד ניתן לחסום ידנית.
 */
public final class AppCategories {

    public static final int OTHER     = 0;
    public static final int SOCIAL    = 1;
    public static final int VIDEO     = 2;
    public static final int GAMES     = 3;
    public static final int BROWSER   = 4;
    public static final int SHOPPING  = 5;
    public static final int DATING    = 6;
    public static final int MESSAGING = 7;

    /** מזהי הקטגוריות לפי סדר התצוגה. ALL אינו קטגוריה אלא "הכל". */
    public static final int ALL = -1;
    public static final int[] DISPLAY_ORDER = {
        ALL, SOCIAL, VIDEO, GAMES, BROWSER, SHOPPING, DATING, MESSAGING, OTHER
    };

    private static final Set<String> SOCIAL_PKGS = new HashSet<>(Arrays.asList(
        "com.instagram.android", "com.facebook.katana", "com.facebook.android",
        "com.facebook.lite", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.snapchat.android", "com.twitter.android", "com.reddit.frontpage",
        "com.pinterest", "com.linkedin.android", "com.tumblr", "com.vkontakte.android",
        "com.discord", "com.tinder", "com.zhiliaoapp.musically.go",
        "com.instagram.lite", "com.twitter.android.lite", "com.threads.android",
        "com.bereal.ft", "com.imgur.mobile", "com.ninegag.android.app"
    ));

    private static final Set<String> VIDEO_PKGS = new HashSet<>(Arrays.asList(
        "com.google.android.youtube", "com.google.android.apps.youtube.music",
        "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient",
        "com.disney.disneyplus", "tv.twitch.android.app", "com.hulu.plus",
        "com.spotify.music", "com.google.android.videos", "com.plexapp.android",
        "com.vimeo.android.videoapp", "org.videolan.vlc", "com.mxtech.videoplayer.ad"
    ));

    private static final Set<String> BROWSER_PKGS = new HashSet<>(Arrays.asList(
        "com.android.chrome", "org.mozilla.firefox", "com.opera.browser",
        "com.opera.mini.native", "com.brave.browser", "com.microsoft.emmx",
        "com.duckduckgo.mobile.android", "com.sec.android.app.sbrowser",
        "com.UCMobile.intl", "com.kiwibrowser.browser", "org.torproject.torbrowser",
        "com.vivaldi.browser", "mark.via.gp", "acr.browser.lightning"
    ));

    private static final Set<String> SHOPPING_PKGS = new HashSet<>(Arrays.asList(
        "com.amazon.mShop.android.shopping", "com.ebay.mobile", "com.alibaba.aliexpresshd",
        "com.contextlogic.wish", "com.zzkko", "com.einnovation.temu", "com.asos.app",
        "com.etsy.android", "com.walmart.android", "com.aliexpress.aer"
    ));

    private static final Set<String> DATING_PKGS = new HashSet<>(Arrays.asList(
        "com.tinder", "com.bumble.app", "com.okcupid.okcupid", "com.pof.android",
        "com.badoo.mobile", "com.grindrapp.android", "co.hinge.app",
        "com.zoosk.zoosk", "com.eharmony", "com.ftw_and_co.happn"
    ));

    private static final Set<String> MESSAGING_PKGS = new HashSet<>(Arrays.asList(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
        "com.viber.voip", "com.facebook.orca", "com.google.android.apps.messaging",
        "org.thoughtcrime.securesms", "com.skype.raider", "jp.naver.line.android",
        "com.imo.android.imoim", "com.google.android.talk"
    ));

    /** מילות מפתח בשם החבילה — גיבוי כשאין התאמה מדויקת. */
    private static final List<String> GAME_HINTS = Arrays.asList(
        ".game", "game.", "games", "puzzle", "casino", "slots", "poker",
        "playrix", "supercell", "king.", "gameloft", "rovio", "zynga",
        "miniclip", "ubisoft", "ea.game", "roblox", "minecraft", "pubg",
        "freefire", "clash", "candycrush", "subwaysurf"
    );

    private AppCategories() {}

    /** הקטגוריה של חבילה. לעולם מחזיר ערך חוקי (OTHER כברירת מחדל). */
    public static int categoryOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) return OTHER;
        String p = pkg.toLowerCase();

        // התאמה מדויקת קודמת — היא המדויקת ביותר.
        // דייטינג לפני חברתי: טינדר מופיע בשתי הרשימות, והשיוך הנכון הוא דייטינג.
        if (DATING_PKGS.contains(p))    return DATING;
        if (SOCIAL_PKGS.contains(p))    return SOCIAL;
        if (VIDEO_PKGS.contains(p))     return VIDEO;
        if (BROWSER_PKGS.contains(p))   return BROWSER;
        if (SHOPPING_PKGS.contains(p))  return SHOPPING;
        if (MESSAGING_PKGS.contains(p)) return MESSAGING;

        // גיבוי לפי מילות מפתח
        for (String hint : GAME_HINTS) {
            if (p.contains(hint)) return GAMES;
        }
        if (p.contains("browser")) return BROWSER;
        if (p.contains("messenger") || p.contains("chat")) return MESSAGING;
        // סוגריים מפורשים: בלעדיהם קדימות && מול || קשה לקריאה ומזמינה טעות
        if (p.contains("shop") || (p.contains("store") && !p.contains("restore"))) {
            return SHOPPING;
        }
        // "tv" נבדק כרכיב שם ולא כתת-מחרוזת: אחרת כל חבילה שבמקרה מכילה
        // את שתי האותיות (למשל ...natives...) הייתה מסווגת כווידאו.
        if (p.contains("video") || p.contains("player")
                || p.startsWith("tv.") || p.contains(".tv.") || p.endsWith(".tv")) {
            return VIDEO;
        }
        if (p.contains("dating")) return DATING;
        if (p.contains("social")) return SOCIAL;

        return OTHER;
    }

    /** מפתח המחרוזת לשם הקטגוריה, לשימוש ב-UI. */
    public static String labelKey(int category) {
        switch (category) {
            case ALL:       return "appcat_all";
            case SOCIAL:    return "appcat_social";
            case VIDEO:     return "appcat_video";
            case GAMES:     return "appcat_games";
            case BROWSER:   return "appcat_browser";
            case SHOPPING:  return "appcat_shopping";
            case DATING:    return "appcat_dating";
            case MESSAGING: return "appcat_messaging";
            default:        return "appcat_other";
        }
    }
}
