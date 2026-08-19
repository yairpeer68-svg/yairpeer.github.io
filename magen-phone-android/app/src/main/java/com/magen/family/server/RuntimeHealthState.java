package com.magen.family.server;

import android.os.SystemClock;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process operational counters used only for privacy-safe health telemetry.
 * No browsing text, URLs, screenshots, account data, or secrets are stored here.
 */
public final class RuntimeHealthState {
    private static final String PROCESS_INSTANCE_ID = UUID.randomUUID().toString();
    private static final AtomicInteger SERVER_FAILURE_STREAK = new AtomicInteger(0);
    private static final AtomicLong LAST_SERVER_SUCCESS_MS = new AtomicLong(0L);
    private static final AtomicLong VPN_RESTART_COUNT = new AtomicLong(0L);

    private RuntimeHealthState() {}

    public static String processInstanceId() { return PROCESS_INSTANCE_ID; }

    public static void serverSuccess() {
        LAST_SERVER_SUCCESS_MS.set(SystemClock.elapsedRealtime());
        SERVER_FAILURE_STREAK.set(0);
    }

    public static void serverFailure() {
        while (true) {
            int cur = SERVER_FAILURE_STREAK.get();
            if (cur >= 1_000_000 || SERVER_FAILURE_STREAK.compareAndSet(cur, cur + 1)) return;
        }
    }

    public static int serverFailureStreak() { return SERVER_FAILURE_STREAK.get(); }

    public static long lastServerSuccessAgeMs() {
        long last = LAST_SERVER_SUCCESS_MS.get();
        return last <= 0L ? -1L : Math.max(0L, SystemClock.elapsedRealtime() - last);
    }

    public static void countVpnRestart() { VPN_RESTART_COUNT.incrementAndGet(); }
    public static long vpnRestartCount() { return VPN_RESTART_COUNT.get(); }
}
