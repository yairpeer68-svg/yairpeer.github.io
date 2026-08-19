package com.magen.family.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pure state machine that guarantees at most one automatic swipe per detected short-form item.
 * It also opens a circuit breaker when many unsafe items arrive back-to-back so the device cannot
 * get stuck in an endless auto-scroll loop.
 */
public final class ShortFormSkipGuard {
    public enum Decision {
        ALLOW,
        COOLDOWN,
        WAITING_FOR_ADVANCE,
        SAME_ITEM,
        CIRCUIT_OPEN
    }

    static final long POST_SKIP_COOLDOWN_MS = 1_800L;
    static final long SAME_ITEM_TTL_MS = 15_000L;
    static final long BURST_WINDOW_MS = 15_000L;
    static final int MAX_SKIPS_PER_BURST = 4;
    static final long CIRCUIT_BREAK_MS = 20_000L;

    private final Deque<Long> recentSkips = new ArrayDeque<>();
    private String lastPackage = "";
    private long lastSignature;
    private long lastSkipAt;
    private boolean awaitingAdvance;
    private long circuitUntil;

    public synchronized Decision evaluate(String pkg, long signature, long now) {
        if (pkg == null) pkg = "";
        prune(now);

        if (now < circuitUntil) return Decision.CIRCUIT_OPEN;

        if (lastSkipAt > 0 && now - lastSkipAt < POST_SKIP_COOLDOWN_MS) {
            return Decision.COOLDOWN;
        }

        if (awaitingAdvance && pkg.equals(lastPackage)) {
            return Decision.WAITING_FOR_ADVANCE;
        }

        if (signature != 0L && signature == lastSignature && pkg.equals(lastPackage)
                && lastSkipAt > 0 && now - lastSkipAt < SAME_ITEM_TTL_MS) {
            return Decision.SAME_ITEM;
        }

        if (recentSkips.size() >= MAX_SKIPS_PER_BURST) {
            circuitUntil = now + CIRCUIT_BREAK_MS;
            awaitingAdvance = false;
            return Decision.CIRCUIT_OPEN;
        }

        lastPackage = pkg;
        lastSignature = signature;
        lastSkipAt = now;
        awaitingAdvance = true;
        recentSkips.addLast(now);
        return Decision.ALLOW;
    }

    /** A real scroll/content change confirms that the previous one-shot swipe advanced the feed. */
    public synchronized void markAdvanced(String pkg, long signature, long now) {
        if (pkg == null || !pkg.equals(lastPackage) || !awaitingAdvance) return;
        if (lastSkipAt <= 0 || now < lastSkipAt) return;
        // Ignore immediate noise generated while the gesture is only starting.
        if (now - lastSkipAt < 120L) return;
        if (signature != 0L && signature == lastSignature) return;
        awaitingAdvance = false;
    }

    /** TYPE_VIEW_SCROLLED is stronger evidence than a content-signature change. */
    public synchronized void markScrolled(String pkg, long now) {
        if (pkg == null || !pkg.equals(lastPackage) || !awaitingAdvance) return;
        if (lastSkipAt <= 0 || now - lastSkipAt < 80L || now - lastSkipAt > 5_000L) return;
        awaitingAdvance = false;
    }

    /** A cancelled gesture should not be retried in a loop. */
    public synchronized void markGestureFailed(String pkg, long now) {
        if (pkg != null && pkg.equals(lastPackage)) awaitingAdvance = false;
        circuitUntil = Math.max(circuitUntil, now + 5_000L);
    }

    public synchronized boolean isAwaitingAdvance(String pkg) {
        return pkg != null && pkg.equals(lastPackage) && awaitingAdvance;
    }

    public synchronized long circuitRemainingMs(long now) {
        return Math.max(0L, circuitUntil - now);
    }

    private void prune(long now) {
        while (!recentSkips.isEmpty() && now - recentSkips.peekFirst() > BURST_WINDOW_MS) {
            recentSkips.removeFirst();
        }
    }
}
