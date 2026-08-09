package com.magen.family.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * בדיקות ל-AhoCorasick — במיוחד גבולות מילה, שהם התיקון שמנע את חסימות
 * השווא ההמוניות (document/analysis/Essex).
 */
public class AhoCorasickTest {

    private AhoCorasick build(String... words) {
        AhoCorasick a = new AhoCorasick();
        for (String w : words) a.addPattern(w);
        a.build();
        return a;
    }

    @Test
    public void shortWord_matchesWholeWord() {
        AhoCorasick a = build("sex");
        assertTrue(a.contains("safe sex talk"));
        assertTrue(a.contains("sex"));
        assertTrue(a.contains("have sex."));
    }

    @Test
    public void shortWord_doesNotMatchInsideOtherWord() {
        AhoCorasick a = build("sex");
        assertFalse("Essex should not match", a.contains("living in Essex"));
        assertFalse("sexual should not match", a.contains("sexual health"));
        assertFalse("unisex should not match", a.contains("unisex bathroom"));
    }

    @Test
    public void falsePositiveWords_areNotBlocked() {
        AhoCorasick a = build("cum", "anal", "sex");
        assertFalse(a.contains("this document is ready"));   // cum
        assertFalse(a.contains("run the analysis now"));     // anal
        assertFalse(a.contains("vacuum the floor"));         // cum
    }

    @Test
    public void longWord_matchesAsSubstring() {
        AhoCorasick a = build("pornography");
        assertTrue(a.contains("view pornography here"));
    }

    @Test
    public void containsRaw_ignoresWordBoundaries() {
        AhoCorasick a = build("porn");
        // גולמי — לשימוש על דומיינים כמו freeporn.com
        assertTrue(a.containsRaw("freeporn"));
        // עם גבולות מילה — לא נתפס בתוך מילה
        assertFalse(a.contains("freeporn"));
    }

    @Test
    public void findFirst_returnsMatchedPattern() {
        AhoCorasick a = build("nude", "sex");
        assertEquals("sex", a.findFirst("a sex scene"));
        assertNull(a.findFirst("nothing here"));
    }

    @Test
    public void build_isIdempotent() {
        AhoCorasick a = new AhoCorasick();
        a.addPattern("sex");
        a.build();
        a.build();   // קריאה חוזרת לא אמורה לשבור או להכפיל
        assertTrue(a.contains("sex"));
        assertFalse(a.contains("Essex"));
    }
}
