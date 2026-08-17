package com.magen.family.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * בדיקות ל-DomainVerdict.normalize — הניקוי הזה רץ לפני *כל* החלטת חסימה
 * (DNS ו-SNI כאחד), ולכן טעות בו משבשת את כל הסינון.
 * בנוסף כאן נבדקת ההיוריסטיקה נגד mirrors, כולל עקיפה ב-leetspeak.
 */
public class DomainVerdictTest {

    @Test
    public void lowercases() {
        assertEquals("pornhub.com", DomainVerdict.normalize("PornHub.COM"));
    }

    @Test
    public void stripsWwwPrefix() {
        assertEquals("example.com", DomainVerdict.normalize("www.example.com"));
    }

    @Test
    public void stripsTrailingDot() {
        assertEquals("example.com", DomainVerdict.normalize("example.com."));
    }

    @Test
    public void stripsPort() {
        assertEquals("example.com", DomainVerdict.normalize("example.com:8443"));
    }

    @Test
    public void combinedNormalization() {
        assertEquals("example.com", DomainVerdict.normalize("WWW.Example.com.:443"));
    }

    @Test
    public void nullAndEmptyAreSafe() {
        assertEquals("", DomainVerdict.normalize(null));
        assertEquals("", DomainVerdict.normalize("   "));
    }

    // ---------------- deLeet ----------------

    @Test
    public void deLeet_mapsDigitsToLetters() {
        assertEquals("pornhub", DomainVerdict.deLeet("p0rnhub"));
        assertEquals("sexcam", DomainVerdict.deLeet("s3xcam"));
        assertEquals("aioste", DomainVerdict.deLeet("41057e"));
    }

    @Test
    public void deLeet_stripsSeparators() {
        assertEquals("pornhub", DomainVerdict.deLeet("porn-hub"));
        assertEquals("xvideos", DomainVerdict.deLeet("x_videos"));
        assertEquals("pornhub", DomainVerdict.deLeet("p0rn-hub"));
    }

    @Test
    public void deLeet_nullSafe() {
        assertEquals("", DomainVerdict.deLeet(null));
    }

    // ---------------- looksLikeAdultMirror ----------------

    @Test
    public void mirror_catchesKnownBrandsAndTokens() {
        assertTrue(DomainVerdict.looksLikeAdultMirror("pornhub.com"));
        assertTrue(DomainVerdict.looksLikeAdultMirror("xnxx2.net"));
        assertTrue(DomainVerdict.looksLikeAdultMirror("my-xvideos.co"));
        assertTrue(DomainVerdict.looksLikeAdultMirror("hentaiworld.xyz"));
    }

    @Test
    public void mirror_catchesLeetspeakEvasion() {
        assertTrue(DomainVerdict.looksLikeAdultMirror("p0rnhub.com"));
        assertTrue(DomainVerdict.looksLikeAdultMirror("porn-hub.xyz"));
        assertTrue(DomainVerdict.looksLikeAdultMirror("s3xcam.io"));
        assertTrue(DomainVerdict.looksLikeAdultMirror("x_videos.net"));
    }

    @Test
    public void mirror_doesNotOverBlockInnocentDomains() {
        assertFalse(DomainVerdict.looksLikeAdultMirror("example.com"));
        assertFalse(DomainVerdict.looksLikeAdultMirror("github.com"));
        // "essex"/"sussex" מכילים "sex" — ולכן הטוקן הוא "sexcam", לא "sex",
        // כדי לא לחסום ערים ושמות תמימים.
        assertFalse(DomainVerdict.looksLikeAdultMirror("essex.gov.uk"));
        assertFalse(DomainVerdict.looksLikeAdultMirror("sussex.ac.uk"));
    }

    @Test
    public void mirror_nullSafe() {
        assertFalse(DomainVerdict.looksLikeAdultMirror(null));
    }
}
