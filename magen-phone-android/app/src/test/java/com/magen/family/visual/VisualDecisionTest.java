package com.magen.family.visual;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisualDecisionTest {
    private VisualPolicy.Config strict() {
        return new VisualPolicy.Config(true,"STRICT",1100,6,true,true,
            .35f,.35f,.45f,.62f,.48f);
    }

    @Test public void strictBlocksSexyTopClassEvenWhenFlat() {
        NsfwResult r=new NsfwResult("sexy",.31f,.18f,.16f,.20f,.15f,.31f,2);
        assertTrue(VisualDecision.shouldBlock(r,strict()));
    }

    @Test public void strictAllowsClearlyNeutral() {
        NsfwResult r=new NsfwResult("neutral",.91f,.02f,.01f,.91f,.02f,.04f,0);
        assertFalse(VisualDecision.shouldBlock(r,strict()));
    }

    @Test public void offAlwaysAllows() {
        VisualPolicy.Config off=new VisualPolicy.Config(false,"OFF",1100,6,true,true,
            .35f,.35f,.45f,.62f,.48f);
        NsfwResult r=new NsfwResult("porn",.99f,0f,0f,0f,.99f,.01f,1);
        assertFalse(VisualDecision.shouldBlock(r,off));
    }
}
