package com.magen.family.visual;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/**
 * Signed visual-filter policy cached on-device.
 * The only writer from the network path is PolicySyncManager after signature verification.
 */
public final class VisualPolicy {
    private static final String PREFS = "magen_visual_policy_v2";

    private VisualPolicy() {}

    public static final class Config {
        public final boolean enabled;
        public final String mode;
        public final long intervalMs;
        public final long burstIntervalMs;
        public final int maxTiles;
        public final boolean blockSexy;
        public final boolean uncertainFailClosed;
        public final float pornThreshold;
        public final float hentaiThreshold;
        public final float sexyThreshold;
        public final float unsafeSumThreshold;
        public final float uncertainThreshold;
        public final boolean temporalEnabled;
        public final long temporalWindowMs;
        public final int temporalMinHits;
        public final int duplicateHammingThreshold;
        public final float immediatePornThreshold;
        public final float immediateHentaiThreshold;
        public final float immediateSexyThreshold;
        public final int maxConsecutiveFailures;

        Config(boolean enabled, String mode, long intervalMs, long burstIntervalMs, int maxTiles,
               boolean blockSexy, boolean uncertainFailClosed,
               float pornThreshold, float hentaiThreshold, float sexyThreshold,
               float unsafeSumThreshold, float uncertainThreshold,
               boolean temporalEnabled, long temporalWindowMs, int temporalMinHits,
               int duplicateHammingThreshold,
               float immediatePornThreshold, float immediateHentaiThreshold,
               float immediateSexyThreshold, int maxConsecutiveFailures) {
            this.enabled = enabled;
            this.mode = mode;
            this.intervalMs = intervalMs;
            this.burstIntervalMs = burstIntervalMs;
            this.maxTiles = maxTiles;
            this.blockSexy = blockSexy;
            this.uncertainFailClosed = uncertainFailClosed;
            this.pornThreshold = pornThreshold;
            this.hentaiThreshold = hentaiThreshold;
            this.sexyThreshold = sexyThreshold;
            this.unsafeSumThreshold = unsafeSumThreshold;
            this.uncertainThreshold = uncertainThreshold;
            this.temporalEnabled = temporalEnabled;
            this.temporalWindowMs = temporalWindowMs;
            this.temporalMinHits = temporalMinHits;
            this.duplicateHammingThreshold = duplicateHammingThreshold;
            this.immediatePornThreshold = immediatePornThreshold;
            this.immediateHentaiThreshold = immediateHentaiThreshold;
            this.immediateSexyThreshold = immediateSexyThreshold;
            this.maxConsecutiveFailures = maxConsecutiveFailures;
        }
    }

    public static Config get(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String mode = p.getString("mode", "STRICT");
        if (mode == null) mode = "STRICT";
        return new Config(
            p.getBoolean("enabled", true),
            mode,
            clamp(p.getLong("interval_ms", 1000L), 500L, 5000L),
            clamp(p.getLong("burst_interval_ms", 650L), 350L, 2500L),
            (int) clamp(p.getInt("max_tiles", 6), 1, 9),
            p.getBoolean("block_sexy", true),
            p.getBoolean("uncertain_fail_closed", true),
            clampF(p.getFloat("porn_threshold", 0.30f), 0.05f, 0.99f),
            clampF(p.getFloat("hentai_threshold", 0.30f), 0.05f, 0.99f),
            clampF(p.getFloat("sexy_threshold", 0.40f), 0.05f, 0.99f),
            clampF(p.getFloat("unsafe_sum_threshold", 0.58f), 0.10f, 0.99f),
            clampF(p.getFloat("uncertain_threshold", 0.44f), 0.10f, 0.99f),
            p.getBoolean("temporal_enabled", true),
            clamp(p.getLong("temporal_window_ms", 3600L), 1200L, 8000L),
            (int) clamp(p.getInt("temporal_min_hits", 2), 1, 4),
            (int) clamp(p.getInt("duplicate_hamming_threshold", 0), 0, 16),
            clampF(p.getFloat("immediate_porn_threshold", 0.72f), 0.20f, 0.99f),
            clampF(p.getFloat("immediate_hentai_threshold", 0.72f), 0.20f, 0.99f),
            clampF(p.getFloat("immediate_sexy_threshold", 0.86f), 0.20f, 0.99f),
            (int) clamp(p.getInt("max_consecutive_failures", 5), 2, 20)
        );
    }

    /** Apply fields from an already signature-verified /v1/policy payload. */
    public static void applySignedPolicy(Context c, JSONObject p) {
        if (p == null) return;
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putBoolean("enabled", p.optBoolean("visual_enabled", true));
        e.putString("mode", sanitizeMode(p.optString("visual_mode", "STRICT")));
        e.putLong("interval_ms", clamp(p.optLong("visual_scan_interval_ms", 1000L), 500L, 5000L));
        e.putLong("burst_interval_ms", clamp(p.optLong("visual_burst_interval_ms", 650L), 350L, 2500L));
        e.putInt("max_tiles", (int) clamp(p.optInt("visual_max_tiles", 6), 1, 9));
        e.putBoolean("block_sexy", p.optBoolean("visual_block_sexy", true));
        e.putBoolean("uncertain_fail_closed", p.optBoolean("visual_uncertain_fail_closed", true));
        e.putFloat("porn_threshold", clampF((float) p.optDouble("visual_porn_threshold", 0.30), 0.05f, 0.99f));
        e.putFloat("hentai_threshold", clampF((float) p.optDouble("visual_hentai_threshold", 0.30), 0.05f, 0.99f));
        e.putFloat("sexy_threshold", clampF((float) p.optDouble("visual_sexy_threshold", 0.40), 0.05f, 0.99f));
        e.putFloat("unsafe_sum_threshold", clampF((float) p.optDouble("visual_unsafe_sum_threshold", 0.58), 0.10f, 0.99f));
        e.putFloat("uncertain_threshold", clampF((float) p.optDouble("visual_uncertain_threshold", 0.44), 0.10f, 0.99f));
        e.putBoolean("temporal_enabled", p.optBoolean("visual_temporal_enabled", true));
        e.putLong("temporal_window_ms", clamp(p.optLong("visual_temporal_window_ms", 3600L), 1200L, 8000L));
        e.putInt("temporal_min_hits", (int) clamp(p.optInt("visual_temporal_min_hits", 2), 1, 4));
        e.putInt("duplicate_hamming_threshold", (int) clamp(p.optInt("visual_duplicate_hamming_threshold", 0), 0, 16));
        e.putFloat("immediate_porn_threshold", clampF((float) p.optDouble("visual_immediate_porn_threshold", 0.72), 0.20f, 0.99f));
        e.putFloat("immediate_hentai_threshold", clampF((float) p.optDouble("visual_immediate_hentai_threshold", 0.72), 0.20f, 0.99f));
        e.putFloat("immediate_sexy_threshold", clampF((float) p.optDouble("visual_immediate_sexy_threshold", 0.86), 0.20f, 0.99f));
        e.putInt("max_consecutive_failures", (int) clamp(p.optInt("visual_max_consecutive_failures", 5), 2, 20));
        e.apply();
    }

    private static String sanitizeMode(String mode) {
        if (mode == null) return "STRICT";
        String m = mode.trim().toUpperCase(java.util.Locale.ROOT);
        return ("STRICT".equals(m) || "BALANCED".equals(m) || "OFF".equals(m)) ? m : "STRICT";
    }

    private static long clamp(long v, long lo, long hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float clampF(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
