package com.magen.family.visual;

/** Pure decision logic kept separate so it can be unit-tested without Android runtime. */
public final class VisualDecision {
    private VisualDecision() {}

    public static float risk(NsfwResult r) {
        if (r == null) return 0f;
        return Math.max(Math.max(r.porn, r.hentai), Math.max(r.sexy, r.unsafeSum));
    }

    /** One-frame hard stop for very strong evidence. */
    public static boolean isImmediateBlock(NsfwResult r, VisualPolicy.Config c) {
        if (r == null || c == null || !c.enabled || "OFF".equalsIgnoreCase(c.mode)) return false;
        if (r.porn >= c.immediatePornThreshold || r.hentai >= c.immediateHentaiThreshold) return true;
        return c.blockSexy && r.sexy >= c.immediateSexyThreshold;
    }

    /** Borderline evidence that participates in the temporal consensus window. */
    public static boolean isSuspicious(NsfwResult r, VisualPolicy.Config c) {
        if (r == null || c == null || !c.enabled || "OFF".equalsIgnoreCase(c.mode)) return false;
        if (r.porn >= c.pornThreshold || r.hentai >= c.hentaiThreshold) return true;
        if (c.blockSexy && r.sexy >= c.sexyThreshold) return true;
        if (r.unsafeSum >= c.unsafeSumThreshold) return true;

        if ("STRICT".equalsIgnoreCase(c.mode)) {
            if (("porn".equals(r.label) || "hentai".equals(r.label)) && r.unsafeSum >= c.uncertainThreshold) return true;
            if (c.blockSexy && "sexy".equals(r.label) && r.unsafeSum >= c.uncertainThreshold) return true;
        }
        return c.uncertainFailClosed && r.unsafeSum >= c.uncertainThreshold;
    }

    /** Non-temporal policy decision used as a compatibility/fallback path. */
    public static boolean shouldBlock(NsfwResult r, VisualPolicy.Config c) {
        if (isImmediateBlock(r, c)) return true;
        if (r == null || c == null || !c.enabled || "OFF".equalsIgnoreCase(c.mode)) return false;
        if (r.porn >= c.pornThreshold || r.hentai >= c.hentaiThreshold) return true;
        if (c.blockSexy && r.sexy >= c.sexyThreshold) return true;
        if (r.unsafeSum >= c.unsafeSumThreshold) return true;
        return c.uncertainFailClosed && isSuspicious(r, c);
    }
}
