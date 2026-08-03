package com.magen.family.filter;

import android.content.Context;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * CategoryLists — דומיינים מקובצים לפי קטגוריה, לחסימה מותנית-הגדרה.
 *
 * הקטגוריה adult מכוסה כבר ע"י ContentFilter ו-RemoteBlocklist; כאן מרוכזות
 * הקטגוריות הנוספות שההורה/המשתמש יכול להדליק בנפרד (הימורים, היכרויות,
 * רשתות חברתיות וכו'). הרשימות קצרות ומכוונות ל"שחקנים הגדולים" — הכיסוי
 * הרחב מגיע מהרשימות המרוחקות.
 */
public final class CategoryLists {

    private static final Set<String> GAMBLING = new HashSet<>(Arrays.asList(
        "bet365.com", "888casino.com", "888poker.com", "pokerstars.com",
        "betway.com", "williamhill.com", "ladbrokes.com", "bwin.com",
        "unibet.com", "party poker.com".replace(" ", ""), "draftkings.com",
        "fanduel.com", "betfair.com", "stake.com", "roobet.com",
        "winner.com", "1xbet.com", "22bet.com", "melbet.com"
    ));

    private static final Set<String> DATING = new HashSet<>(Arrays.asList(
        "tinder.com", "bumble.com", "okcupid.com", "match.com", "pof.com",
        "badoo.com", "grindr.com", "hinge.co", "adultfriendfinder.com",
        "ashleymadison.com", "meetic.com", "jdate.com"
    ));

    private static final Set<String> SOCIAL = new HashSet<>(Arrays.asList(
        "facebook.com", "instagram.com", "tiktok.com", "snapchat.com",
        "twitter.com", "x.com", "reddit.com", "tumblr.com", "9gag.com",
        "discord.com", "twitch.tv", "onlyfans.com", "fansly.com"
    ));

    private static final Set<String> SHOPPING = new HashSet<>(Arrays.asList(
        "amazon.com", "ebay.com", "aliexpress.com", "wish.com",
        "shein.com", "temu.com", "asos.com"
    ));

    private CategoryLists() {}

    /** האם המארח חסום לפי אחת הקטגוריות שהמשתמש הדליק? */
    public static boolean isBlockedByCategory(Context ctx, String rootDomain) {
        if (rootDomain == null) return false;

        if (FilterPolicy.isCategoryOn(ctx, FilterPolicy.CAT_GAMBLING)
                && GAMBLING.contains(rootDomain)) return true;
        if (FilterPolicy.isCategoryOn(ctx, FilterPolicy.CAT_DATING)
                && DATING.contains(rootDomain)) return true;
        if (FilterPolicy.isCategoryOn(ctx, FilterPolicy.CAT_SOCIAL)
                && SOCIAL.contains(rootDomain)) return true;
        if (FilterPolicy.isCategoryOn(ctx, FilterPolicy.CAT_SHOPPING)
                && SHOPPING.contains(rootDomain)) return true;

        return false;
    }
}
