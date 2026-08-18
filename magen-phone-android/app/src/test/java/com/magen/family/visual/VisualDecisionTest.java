package com.magen.family.visual;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisualDecisionTest {
    private VisualPolicy.Config strict() {
        return cfg(true, "STRICT");
    }

    /**
     * ספי הבסיס נשארים כפי שהיו כאן מאז שהמבחן נכתב; שדות ה-temporal
     * וה-immediate מקבלים את ערכי ברירת המחדל של VisualPolicy.get, כדי
     * שהבדיקות ימשיכו לבחון את מסלול ההחלטה הלא-temporal בלבד.
     */
    private static VisualPolicy.Config cfg(boolean enabled, String mode) {
        return new VisualPolicy.Config(
            enabled, mode, 1100L, 650L, 6,
            true, true,
            .35f, .35f, .45f, .62f, .48f,
            true, 3600L, 2, 0,
            .72f, .72f, .86f, 5);
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
        VisualPolicy.Config off=cfg(false, "OFF");
        NsfwResult r=new NsfwResult("porn",.99f,0f,0f,0f,.99f,.01f,1);
        assertFalse(VisualDecision.shouldBlock(r,off));
    }
}
