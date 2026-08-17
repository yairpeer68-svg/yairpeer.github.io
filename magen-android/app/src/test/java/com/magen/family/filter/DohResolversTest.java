package com.magen.family.filter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * בדיקות ל-DohResolvers — מבטיחות שמארחי DoH ידועים נחסמים ושאתרים
 * לגיטימיים לא נתפסים בטעות. זה שכבת ההגנה מפני עקיפת סינון ה-DNS.
 */
public class DohResolversTest {

    @Test
    public void knownExactHosts_areBlocked() {
        assertTrue(DohResolvers.isDohHost("dns.google"));
        assertTrue(DohResolvers.isDohHost("cloudflare-dns.com"));
        assertTrue(DohResolvers.isDohHost("mozilla.cloudflare-dns.com"));
        assertTrue(DohResolvers.isDohHost("one.one.one.one"));
        assertTrue(DohResolvers.isDohHost("dns.quad9.net"));
        assertTrue(DohResolvers.isDohHost("dns.adguard.com"));
    }

    @Test
    public void perUserSubdomains_matchBySuffix() {
        assertTrue(DohResolvers.isDohHost("abcd1234.dns.nextdns.io"));
        assertTrue(DohResolvers.isDohHost("dns.nextdns.io"));
        assertTrue(DohResolvers.isDohHost("x.dns.controld.com"));
        assertTrue(DohResolvers.isDohHost("dns.controld.com"));
    }

    @Test
    public void normalization_isCaseAndDotInsensitive() {
        assertTrue(DohResolvers.isDohHost("DNS.Google"));
        assertTrue(DohResolvers.isDohHost("dns.google."));
        assertTrue(DohResolvers.isDohHost("www.cloudflare-dns.com"));
    }

    @Test
    public void legitimateHosts_areNotBlocked() {
        assertFalse(DohResolvers.isDohHost("google.com"));
        assertFalse(DohResolvers.isDohHost("example.com"));
        assertFalse(DohResolvers.isDohHost("news.ynet.co.il"));
        assertFalse(DohResolvers.isDohHost("wikipedia.org"));
        assertFalse(DohResolvers.isDohHost(""));
        assertFalse(DohResolvers.isDohHost(null));
    }
}
