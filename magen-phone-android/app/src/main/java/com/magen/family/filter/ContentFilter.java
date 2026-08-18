package com.magen.family.filter;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.magen.family.MagenApp;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * מנוע סינון תוכן מרכזי
 * בודק URLs, שמות אפליקציות ומילות חיפוש
 */
public class ContentFilter {

    private static final String TAG = "ContentFilter";

    // ===== רשימת דומיינים לחסימה =====
    private static final Set<String> BLOCKED_DOMAINS = new HashSet<>(Arrays.asList(
        // אתרי תוכן למבוגרים - קטגוריה ראשית
        "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
        "redtube.com", "youporn.com", "tube8.com", "spankbang.com",
        "beeg.com", "tnaflix.com", "drtuber.com", "hclips.com",
        "txxx.com", "hdtube.porn", "fapster.xxx", "porntrex.com",
        "porndoe.com", "fuq.com", "4tube.com", "hardsextube.com",
        "3movs.com", "sunporno.com", "porndig.com", "slutload.com",
        "empflix.com", "pornoxo.com", "nuvid.com", "xtube.com",
        "playvids.com", "vporn.com", "pornid.xxx", "sex.com",
        "clips4sale.com", "onlyfans.com", "manyvids.com",
        "brazzers.com", "realitykings.com", "bangbros.com",
        "mofos.com", "naughtyamerica.com", "digitalplayground.com",
        "wicked.com", "vivid.com", "penthouse.com", "hustler.com",
        "playboy.com",

        // סיומות לחסימה (יטופל בנפרד)
        // .xxx .porn .sex .adult - בregex

        // אתרי הימורים
        "bet365.com", "888casino.com", "pokerstars.com",
        "betway.com", "williamhill.com", "ladbrokes.com",

        // דוגמאות נוספות שניתן להרחיב
        "webcam.com", "chaturbate.com", "livejasmin.com",
        "bongacams.com", "myfreecams.com", "stripchat.com",
        "cam4.com", "camsoda.com", "flirt4free.com"
    ));

    // ===== מילות מפתח לחסימה בכתובות URL =====
    private static final Set<String> BLOCKED_KEYWORDS = new HashSet<>(Arrays.asList(
        "porn", "xxx", "sex", "nude", "naked", "erotic",
        "adult-content", "18plus", "hentai", "nsfw"
    ));

    // ===== דפדפנים לפיקוח =====
    public static final Set<String> BROWSER_PACKAGES = new HashSet<>(Arrays.asList(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.microsoft.emmx",        // Edge
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.UCMobile.intl",
        "com.uc.browser.en",
        "com.kiwibrowser.browser",
        "mark.via.gp",               // Via Browser
        "com.sec.android.app.sbrowser", // Samsung Internet
        "com.android.browser",
        "org.mozilla.firefox_beta",
        "com.duckduckgo.mobile.android"
    ));

    // ===== רגקס לסיומות למבוגרים =====
    private static final Pattern ADULT_TLD_PATTERN =
        Pattern.compile(".*\\.(xxx|porn|sex|adult|sexy|fuck|cock|pussy)$",
            Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final AhoCorasick keywordMatcher;

    public ContentFilter(Context context) {
        this.context = context;
        this.keywordMatcher = new AhoCorasick();
        keywordMatcher.addAll(BLOCKED_KEYWORDS);
        keywordMatcher.build();
    }

    /**
     * הפונקציה הראשית - בודקת אם URL צריך להיחסם.
     * שינוי מהגרסה הקודמת: לא מעדכנים את המונה כאן — המונה זז למקום אחד מרכזי
     * (ה-caller שגרם לחסימה בפועל), כדי שלא נסכן הכפלה אם אותה URL נבדק כמה פעמים.
     */
    public boolean shouldBlock(String url) {
        if (url == null || url.isEmpty()) return false;

        String urlLower = url.toLowerCase().trim();
        String domain = extractDomain(urlLower);

        // ההכרעה המרכזית — כוללת גם את הרשימה המרוחקת ואת ה-allow list
        if (DomainVerdict.isBlocked(context, domain)) {
            Log.d(TAG, "BLOCKED (host): " + domain);
            recordBlocked();
            return true;
        }
        // מילת מפתח בנתיב/בשאילתה, מעבר לשם הדומיין עצמו
        if (containsBlockedKeyword(urlLower)) {
            Log.d(TAG, "BLOCKED (keyword): " + urlLower);
            recordBlocked();
            return true;
        }
        return false;
    }

    /**
     * בדיקת שם מארח בלבד (בלי סכימה/נתיב) מול הרשימות המקומיות.
     * זו נקודת הכניסה ש-DomainVerdict קורא לה — ולכן היא *אסורה* לקרוא
     * חזרה ל-DomainVerdict, אחרת נוצרת רקורסיה אינסופית.
     */
    public boolean isHostBlocked(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase().trim();
        if (isDomainBlocked(h)) return true;
        return ADULT_TLD_PATTERN.matcher(h).matches();
    }

    private void recordBlocked() {
        try {
            ((MagenApp) context.getApplicationContext()).incrementBlockedCount();
        } catch (Exception ignored) {}
    }

    /**
     * בדיקת שם אפליקציה
     */
    public boolean isAppBlocked(String packageName) {
        if (packageName == null) return false;
        SharedPreferences prefs = ((MagenApp) context.getApplicationContext()).getPrefs();
        // קרא רשימת אפליקציות חסומות בהתאמה אישית
        Set<String> blockedApps = prefs.getStringSet("blocked_apps", new HashSet<>());
        return blockedApps.contains(packageName);
    }

    /**
     * חילוץ דומיין מ-URL
     */
    public String extractDomain(String url) {
        return HostUtil.extractHost(url);
    }

    /**
     * בדיקה אם הדומיין או דומיין-על שלו ברשימה השחורה
     */
    private boolean isDomainBlocked(String domain) {
        if (BLOCKED_DOMAINS.contains(domain)) return true;
        // בדוק גם תת-דומיינים: video.pornhub.com -> pornhub.com
        String[] parts = domain.split("\\.");
        if (parts.length >= 2) {
            String rootDomain = parts[parts.length - 2] + "." + parts[parts.length - 1];
            if (BLOCKED_DOMAINS.contains(rootDomain)) return true;
        }
        return false;
    }

    private boolean containsBlockedKeyword(String url) {
        SharedPreferences prefs = ((MagenApp) context.getApplicationContext()).getPrefs();
        if (!prefs.getBoolean(MagenApp.KEY_BLOCK_ADULT, true)) return false;
        // רמת סינון LIGHT מסתמכת על דומיינים בלבד — בלי מילות מפתח
        if (!FilterPolicy.useKeywords(context)) return false;
        // על URL משתמשים בהתאמה גולמית ולא בגבולות מילה:
        // "freeporn.com" חייב להיתפס למרות שאין שם רווחים.
        return keywordMatcher.containsRaw(url);
    }

    public Set<String> getBlockedDomains() {
        return new HashSet<>(BLOCKED_DOMAINS);
    }
}
