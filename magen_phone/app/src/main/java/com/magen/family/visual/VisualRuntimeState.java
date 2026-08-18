package com.magen.family.visual;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local health counters for the visual filter. Only counters/metadata are exposed;
 * screenshots and image bytes never leave the visual package.
 */
public final class VisualRuntimeState {
    private static final AtomicBoolean MODEL_READY = new AtomicBoolean(false);
    private static final AtomicLong SCANS = new AtomicLong();
    private static final AtomicLong BLOCKS = new AtomicLong();
    private static final AtomicLong DUPLICATE_SKIPS = new AtomicLong();
    private static final AtomicInteger CONSECUTIVE_FAILURES = new AtomicInteger();
    private static final AtomicLong LAST_SUCCESS_AT = new AtomicLong();
    private static final AtomicLong LAST_FAILURE_AT = new AtomicLong();

    private VisualRuntimeState() {}

    public static void modelReady() { MODEL_READY.set(true); }
    public static void modelUnavailable() { MODEL_READY.set(false); }
    public static void scanCompleted() {
        SCANS.incrementAndGet();
        CONSECUTIVE_FAILURES.set(0);
        LAST_SUCCESS_AT.set(System.currentTimeMillis());
    }
    public static void blocked() { BLOCKS.incrementAndGet(); }
    public static void duplicateSkipped() { DUPLICATE_SKIPS.incrementAndGet(); }
    public static void failed() {
        CONSECUTIVE_FAILURES.incrementAndGet();
        LAST_FAILURE_AT.set(System.currentTimeMillis());
    }

    public static boolean isModelReady() { return MODEL_READY.get(); }
    public static long scans() { return SCANS.get(); }
    public static long blocks() { return BLOCKS.get(); }
    public static long duplicateSkips() { return DUPLICATE_SKIPS.get(); }
    public static int consecutiveFailures() { return CONSECUTIVE_FAILURES.get(); }
    public static long lastSuccessAt() { return LAST_SUCCESS_AT.get(); }
    public static long lastFailureAt() { return LAST_FAILURE_AT.get(); }
}
