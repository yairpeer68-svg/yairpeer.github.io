package com.magen.family.service.vpn;

/** Pure helpers for TCP receive-window handling, kept Android-free for JVM tests. */
final class TcpWindow {
    private TcpWindow() {}

    static int scale(int rawWindow, int scale) {
        if (rawWindow < 0) rawWindow = 0;
        int safeScale = Math.max(0, Math.min(scale, 14));
        long value = ((long) rawWindow) << safeScale;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    static int available(long nextSeq, long firstUnacked, int advertisedWindow) {
        if (advertisedWindow <= 0) return 0;
        long inFlight = Math.max(0L, nextSeq - firstUnacked);
        long remaining = (long) advertisedWindow - inFlight;
        if (remaining <= 0) return 0;
        return remaining > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    /** Parse RFC 7323 Window Scale option from a TCP header; 0 if absent/malformed. */
    static int parseWindowScale(byte[] packet, int ipHeaderLength, int tcpHeaderLength) {
        if (packet == null || ipHeaderLength < 0 || tcpHeaderLength < 20
                || ipHeaderLength > packet.length - 20
                || tcpHeaderLength > packet.length - ipHeaderLength) return 0;
        int i = ipHeaderLength + 20;
        int end = ipHeaderLength + tcpHeaderLength;
        while (i < end) {
            int kind = packet[i] & 0xFF;
            if (kind == 0) break;       // EOL
            if (kind == 1) { i++; continue; } // NOP
            if (i + 1 >= end) break;
            int len = packet[i + 1] & 0xFF;
            if (len < 2 || i + len > end) break;
            if (kind == 3 && len == 3) return Math.min(packet[i + 2] & 0xFF, 14);
            i += len;
        }
        return 0;
    }
}
