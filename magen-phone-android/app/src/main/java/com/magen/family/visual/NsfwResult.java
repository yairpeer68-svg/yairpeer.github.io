package com.magen.family.visual;

import java.util.Locale;

public final class NsfwResult {
    public final String label;
    public final float topScore;
    public final float porn;
    public final float hentai;
    public final float sexy;
    public final float neutral;
    public final float drawings;
    public final float unsafeSum;
    public final int tileIndex;

    public NsfwResult(String label, float topScore, float drawings, float hentai,
                      float neutral, float porn, float sexy, int tileIndex) {
        this.label = label;
        this.topScore = topScore;
        this.drawings = drawings;
        this.hentai = hentai;
        this.neutral = neutral;
        this.porn = porn;
        this.sexy = sexy;
        this.unsafeSum = Math.min(1f, Math.max(0f, porn + hentai + sexy));
        this.tileIndex = tileIndex;
    }

    public String compact() {
        return String.format(Locale.US,
            "label=%s top=%.3f unsafe=%.3f porn=%.3f hentai=%.3f sexy=%.3f tile=%d",
            label, topScore, unsafeSum, porn, hentai, sexy, tileIndex);
    }
}
