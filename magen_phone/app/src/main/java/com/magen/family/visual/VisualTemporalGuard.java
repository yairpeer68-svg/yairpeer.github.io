package com.magen.family.visual;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Temporal smoothing for borderline visual detections. Strong detections still block in one
 * frame; borderline detections need repeated evidence in a short window. This reduces flicker
 * and false positives without weakening recall on explicit content.
 */
public final class VisualTemporalGuard {
    private static final class Hit {
        final long at;
        final float risk;
        Hit(long at, float risk) { this.at = at; this.risk = risk; }
    }

    private final Deque<Hit> hits = new ArrayDeque<>();
    private String packageName = "";

    public synchronized boolean observe(String pkg, NsfwResult result, VisualPolicy.Config cfg, long now) {
        if (pkg == null) pkg = "";
        if (!pkg.equals(packageName)) {
            packageName = pkg;
            hits.clear();
        }
        prune(now, cfg.temporalWindowMs);

        if (VisualDecision.isImmediateBlock(result, cfg)) {
            hits.clear();
            return true;
        }
        if (!cfg.temporalEnabled) return VisualDecision.shouldBlock(result, cfg);

        if (VisualDecision.isSuspicious(result, cfg)) {
            hits.addLast(new Hit(now, VisualDecision.risk(result)));
        } else if (result != null && result.neutral >= 0.80f) {
            // A strong safe frame should quickly decay stale borderline evidence.
            hits.clear();
        }
        prune(now, cfg.temporalWindowMs);
        return hits.size() >= cfg.temporalMinHits;
    }

    public synchronized void reset() {
        hits.clear();
        packageName = "";
    }

    private void prune(long now, long windowMs) {
        while (!hits.isEmpty() && now - hits.peekFirst().at > windowMs) hits.removeFirst();
    }
}
