package com.magen.family.server;

import org.junit.Test;
import static org.junit.Assert.*;

public class ContentIntelligenceStateTest {
    @Test public void sha256SubjectHashIsStableAndFullLength() {
        String a = ContentIncidentReporter.sha256("example.com");
        String b = ContentIncidentReporter.sha256("example.com");
        assertEquals(64, a.length());
        assertEquals(a, b);
        assertTrue(a.matches("[0-9a-f]{64}"));
    }

    @Test public void runtimeCountersAreMonotonic() {
        long d = IntelligenceRuntimeState.domainRequests();
        long t = IntelligenceRuntimeState.textRequests();
        long c = IntelligenceRuntimeState.cacheHits();
        long b = IntelligenceRuntimeState.blocks();
        long f = IntelligenceRuntimeState.failures();
        IntelligenceRuntimeState.domainRequest();
        IntelligenceRuntimeState.textRequest();
        IntelligenceRuntimeState.cacheHit();
        IntelligenceRuntimeState.block();
        IntelligenceRuntimeState.failure();
        assertEquals(d + 1, IntelligenceRuntimeState.domainRequests());
        assertEquals(t + 1, IntelligenceRuntimeState.textRequests());
        assertEquals(c + 1, IntelligenceRuntimeState.cacheHits());
        assertEquals(b + 1, IntelligenceRuntimeState.blocks());
        assertEquals(f + 1, IntelligenceRuntimeState.failures());
    }
}
