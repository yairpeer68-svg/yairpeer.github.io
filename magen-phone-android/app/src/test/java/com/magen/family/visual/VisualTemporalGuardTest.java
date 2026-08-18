package com.magen.family.visual;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisualTemporalGuardTest {
    private static VisualPolicy.Config cfg() {
        return new VisualPolicy.Config(
            true, "STRICT", 1000L, 650L, 6,
            true, true,
            0.30f, 0.30f, 0.40f, 0.58f, 0.44f,
            true, 3600L, 2, 3,
            0.72f, 0.72f, 0.86f, 5);
    }

    @Test public void immediateExplicitBlocksInOneFrame() {
        NsfwResult r = new NsfwResult("porn", 0.90f, 0.01f, 0.02f, 0.05f, 0.90f, 0.02f, 0);
        assertTrue(VisualDecision.isImmediateBlock(r, cfg()));
    }

    @Test public void borderlineNeedsTemporalConsensus() {
        VisualTemporalGuard g = new VisualTemporalGuard();
        NsfwResult r = new NsfwResult("sexy", 0.55f, 0.02f, 0.02f, 0.35f, 0.06f, 0.55f, 2);
        assertFalse(g.observe("com.example", r, cfg(), 1000L));
        assertTrue(g.observe("com.example", r, cfg(), 1800L));
    }

    @Test public void strongSafeFrameClearsBorderlineHistory() {
        VisualTemporalGuard g = new VisualTemporalGuard();
        NsfwResult risk = new NsfwResult("sexy", 0.55f, 0.02f, 0.02f, 0.35f, 0.06f, 0.55f, 1);
        NsfwResult safe = new NsfwResult("neutral", 0.90f, 0.02f, 0.01f, 0.90f, 0.02f, 0.05f, 0);
        assertFalse(g.observe("com.example", risk, cfg(), 1000L));
        assertFalse(g.observe("com.example", safe, cfg(), 1500L));
        assertFalse(g.observe("com.example", risk, cfg(), 1800L));
    }

    @Test public void packageChangeDoesNotCarryHistory() {
        VisualTemporalGuard g = new VisualTemporalGuard();
        NsfwResult r = new NsfwResult("sexy", 0.55f, 0.02f, 0.02f, 0.35f, 0.06f, 0.55f, 1);
        assertFalse(g.observe("a", r, cfg(), 1000L));
        assertFalse(g.observe("b", r, cfg(), 1500L));
    }
}
