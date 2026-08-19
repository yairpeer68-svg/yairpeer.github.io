package com.magen.family.mitm;

import java.util.concurrent.atomic.AtomicLong;

/** Process-local counters only; no visited URLs/headers/body data are persisted. */
public final class MitmRuntimeState {
    private static final AtomicLong proxyConnections=new AtomicLong();
    private static final AtomicLong intercepted=new AtomicLong();
    private static final AtomicLong tunneled=new AtomicLong();
    private static final AtomicLong blocked=new AtomicLong();
    private static final AtomicLong certIssued=new AtomicLong();
    private static final AtomicLong fallback=new AtomicLong();
    private static final AtomicLong failures=new AtomicLong();
    private MitmRuntimeState(){}
    public static void proxyConnection(){proxyConnections.incrementAndGet();}
    public static void intercept(){intercepted.incrementAndGet();}
    public static void tunnel(){tunneled.incrementAndGet();}
    public static void block(){blocked.incrementAndGet();}
    public static void certIssue(){certIssued.incrementAndGet();}
    public static void fallback(){fallback.incrementAndGet();}
    public static void failure(){failures.incrementAndGet();}
    public static long proxyConnections(){return proxyConnections.get();}
    public static long interceptions(){return intercepted.get();}
    public static long tunnels(){return tunneled.get();}
    public static long blocks(){return blocked.get();}
    public static long certsIssued(){return certIssued.get();}
    public static long fallbacks(){return fallback.get();}
    public static long failures(){return failures.get();}
}
