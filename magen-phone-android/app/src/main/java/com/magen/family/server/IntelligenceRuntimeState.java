package com.magen.family.server;

import java.util.concurrent.atomic.AtomicLong;

/** Process-local counters for Content Intelligence observability. */
public final class IntelligenceRuntimeState {
    private static final AtomicLong DOMAIN_REQUESTS = new AtomicLong();
    private static final AtomicLong TEXT_REQUESTS = new AtomicLong();
    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong BLOCKS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();

    private IntelligenceRuntimeState() {}

    public static void domainRequest() { DOMAIN_REQUESTS.incrementAndGet(); }
    public static void textRequest() { TEXT_REQUESTS.incrementAndGet(); }
    public static void cacheHit() { CACHE_HITS.incrementAndGet(); }
    public static void block() { BLOCKS.incrementAndGet(); }
    public static void failure() { FAILURES.incrementAndGet(); }

    public static long domainRequests() { return DOMAIN_REQUESTS.get(); }
    public static long textRequests() { return TEXT_REQUESTS.get(); }
    public static long cacheHits() { return CACHE_HITS.get(); }
    public static long blocks() { return BLOCKS.get(); }
    public static long failures() { return FAILURES.get(); }
}
