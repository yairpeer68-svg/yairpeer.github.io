package com.magen.family.filter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * בדיקות ל-DomainVerdict.normalize — הניקוי הזה רץ לפני *כל* החלטת חסימה
 * (DNS ו-SNI כאחד), ולכן טעות בו משבשת את כל הסינון.
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
}
