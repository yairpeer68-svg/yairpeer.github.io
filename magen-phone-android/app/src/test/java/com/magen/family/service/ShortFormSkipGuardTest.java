package com.magen.family.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShortFormSkipGuardTest {
    @Test public void exactlyOneSwipeUntilAdvance() {
        ShortFormSkipGuard g = new ShortFormSkipGuard();
        assertEquals(ShortFormSkipGuard.Decision.ALLOW, g.evaluate("tiktok", 11L, 1_000L));
        assertEquals(ShortFormSkipGuard.Decision.COOLDOWN, g.evaluate("tiktok", 11L, 2_000L));
        assertEquals(ShortFormSkipGuard.Decision.WAITING_FOR_ADVANCE, g.evaluate("tiktok", 22L, 3_000L));
        g.markScrolled("tiktok", 3_100L);
        assertFalse(g.isAwaitingAdvance("tiktok"));
        assertEquals(ShortFormSkipGuard.Decision.ALLOW, g.evaluate("tiktok", 22L, 3_200L));
    }

    @Test public void sameItemIsNotSkippedTwice() {
        ShortFormSkipGuard g = new ShortFormSkipGuard();
        assertEquals(ShortFormSkipGuard.Decision.ALLOW, g.evaluate("yt", 77L, 10_000L));
        g.markScrolled("yt", 10_300L);
        assertEquals(ShortFormSkipGuard.Decision.SAME_ITEM, g.evaluate("yt", 77L, 12_000L));
    }

    @Test public void circuitBreakerStopsEndlessBadFeed() {
        ShortFormSkipGuard g = new ShortFormSkipGuard();
        long t = 100_000L;
        for (int i = 0; i < ShortFormSkipGuard.MAX_SKIPS_PER_BURST; i++) {
            assertEquals(ShortFormSkipGuard.Decision.ALLOW, g.evaluate("ig", 100 + i, t));
            g.markScrolled("ig", t + 200L);
            t += 2_000L;
        }
        assertEquals(ShortFormSkipGuard.Decision.CIRCUIT_OPEN, g.evaluate("ig", 999L, t));
        assertTrue(g.circuitRemainingMs(t) > 0L);
    }

    @Test public void cancelledGestureCannotImmediatelyRetry() {
        ShortFormSkipGuard g = new ShortFormSkipGuard();
        assertEquals(ShortFormSkipGuard.Decision.ALLOW, g.evaluate("tt", 1L, 1_000L));
        g.markGestureFailed("tt", 1_200L);
        assertEquals(ShortFormSkipGuard.Decision.CIRCUIT_OPEN, g.evaluate("tt", 2L, 3_000L));
    }

    @Test public void contentSignatureCanConfirmAdvance() {
        ShortFormSkipGuard g = new ShortFormSkipGuard();
        assertEquals(ShortFormSkipGuard.Decision.ALLOW, g.evaluate("tt", 1L, 5_000L));
        g.markAdvanced("tt", 1L, 5_500L);
        assertTrue(g.isAwaitingAdvance("tt"));
        g.markAdvanced("tt", 2L, 5_600L);
        assertFalse(g.isAwaitingAdvance("tt"));
    }
}
