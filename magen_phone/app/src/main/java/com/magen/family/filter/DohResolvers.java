package com.magen.family.filter;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * DohResolvers — זיהוי מארחים של DNS-over-HTTPS / DNS-over-TLS ידועים.
 *
 * למה זה קיים:
 *   סינון ה-DNS שלנו נשבר אם דפדפן או אפליקציה עוקפים אותו דרך DoH — הם
 *   שולחים את שאילתות ה-DNS *מוצפנות בתוך HTTPS* לשרת חיצוני (למשל
 *   cloudflare-dns.com), ואז אנחנו לא רואים אילו דומיינים נפתחים. כבר יש
 *   חסימה לפי כתובות-IP של ה-resolvers, אבל resolvers חדשים ו-endpoints
 *   מבוססי-שם דורשים גם חסימה לפי *שם מארח*: כשחוסמים את שם ה-DoH,
 *   הן ההיפוך של השם והן חיבור ה-TLS אליו (לפי SNI) נחסמים, והמכשיר נופל
 *   חזרה ל-DNS רגיל — שאותו אנחנו כן מסננים.
 *
 * כולו לוגיקה טהורה (בלי Context) כדי שיהיה ניתן לבדיקה ב-JUnit.
 */
public final class DohResolvers {

    /** מארחי DoH/DoT מדויקים. */
    private static final Set<String> EXACT = new HashSet<>(Arrays.asList(
        // Google
        "dns.google", "dns.google.com", "dns64.dns.google",
        // Cloudflare
        "cloudflare-dns.com", "mozilla.cloudflare-dns.com", "chrome.cloudflare-dns.com",
        "family.cloudflare-dns.com", "security.cloudflare-dns.com", "one.one.one.one",
        // Quad9
        "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
        // OpenDNS
        "doh.opendns.com", "doh.familyshield.opendns.com",
        // AdGuard
        "dns.adguard.com", "dns-family.adguard.com", "dns-unfiltered.adguard.com",
        "dns.adguard-dns.com", "family.adguard-dns.com", "unfiltered.adguard-dns.com",
        // CleanBrowsing
        "doh.cleanbrowsing.org",
        // dns.sb
        "doh.dns.sb", "dns.sb",
        // Mullvad
        "doh.mullvad.net", "dns.mullvad.net", "adblock.dns.mullvad.net",
        // Yandex
        "common.dot.dns.yandex.net", "dns.yandex.ru",
        // אחרים נפוצים
        "doh.libredns.gr", "ordns.he.net", "doh.tiar.app", "dns.digitale-gesellschaft.ch"
    ));

    /**
     * ספקים עם endpoint לכל משתמש (סאב-דומיין דינמי). מתאימים לפי סיומת,
     * למשל abcd1234.dns.nextdns.io או p2.freedns.controld.com.
     */
    private static final String[] SUFFIXES = {
        ".dns.nextdns.io", "dns.nextdns.io",
        ".dns.controld.com", "dns.controld.com",
        ".dns.adguard-dns.com",
        ".doh.dns.sb"
    };

    private DohResolvers() {}

    /** מנקה שם מארח: lowercase, בלי נקודה סופית, בלי www. */
    private static String norm(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase();
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.startsWith("www.")) h = h.substring(4);
        return h;
    }

    /** האם המארח הוא ספק DoH/DoT ידוע? */
    public static boolean isDohHost(String host) {
        String h = norm(host);
        if (h.isEmpty()) return false;
        if (EXACT.contains(h)) return true;
        for (String s : SUFFIXES) {
            if (s.startsWith(".")) {
                if (h.endsWith(s)) return true;
            } else if (h.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
