package com.magen.family.service.vpn;

/** Pure RFC-793 style helpers for mapping 32-bit TCP sequence numbers onto a nearby absolute long. */
final class TcpSeq {
    private static final long MOD = 1L << 32;
    private static final long HALF = 1L << 31;
    private static final long MASK = MOD - 1L;
    private TcpSeq() {}

    static long unwrap(long seq32, long reference) {
        long low = seq32 & MASK;
        long base = reference & ~MASK;
        long candidate = base | low;
        long delta = candidate - reference;
        if (delta > HALF) candidate -= MOD;
        else if (delta < -HALF) candidate += MOD;
        return candidate;
    }

    static boolean acknowledges(long ack32, long firstUnacked, long nextSeq) {
        long ack = unwrap(ack32, firstUnacked);
        return ack > firstUnacked && ack <= nextSeq;
    }
}
