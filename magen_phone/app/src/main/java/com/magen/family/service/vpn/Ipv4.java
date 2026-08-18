package com.magen.family.service.vpn;

/**
 * Ipv4 — קריאה ובנייה של חבילות IPv4/UDP/TCP גולמיות.
 *
 * ה-TUN מספק חבילות IP גולמיות, ולכן כל תשובה שאנחנו מזריקים בחזרה למכשיר
 * חייבת להיבנות ידנית עם checksums תקינים — אחרת ערימת הרשת של אנדרואיד
 * זורקת אותה בשקט וזה נראה כמו "אין אינטרנט".
 */
public final class Ipv4 {

    public static final int PROTO_ICMP = 1;
    public static final int PROTO_TCP  = 6;
    public static final int PROTO_UDP  = 17;

    // דגלי TCP
    public static final int FIN = 0x01;
    public static final int SYN = 0x02;
    public static final int RST = 0x04;
    public static final int PSH = 0x08;
    public static final int ACK = 0x10;

    private Ipv4() {}

    // ---------------- קריאה ----------------

    public static int version(byte[] p)   { return (p[0] >> 4) & 0x0F; }
    public static int ihl(byte[] p)       { return (p[0] & 0x0F) * 4; }
    public static int totalLength(byte[] p) { return ((p[2] & 0xFF) << 8) | (p[3] & 0xFF); }
    public static int protocol(byte[] p)  { return p[9] & 0xFF; }
    public static int srcIp(byte[] p)     { return readInt(p, 12); }
    public static int dstIp(byte[] p)     { return readInt(p, 16); }

    /** האם החבילה מפוצלת (fragment)? חבילות כאלה לא נתמכות ונזרקות. */
    public static boolean isFragment(byte[] p) {
        int flagsFrag = ((p[6] & 0xFF) << 8) | (p[7] & 0xFF);
        boolean moreFragments = (flagsFrag & 0x2000) != 0;
        int offset = flagsFrag & 0x1FFF;
        return moreFragments || offset != 0;
    }

    public static int srcPort(byte[] p, int ihl) {
        return ((p[ihl] & 0xFF) << 8) | (p[ihl + 1] & 0xFF);
    }

    public static int dstPort(byte[] p, int ihl) {
        return ((p[ihl + 2] & 0xFF) << 8) | (p[ihl + 3] & 0xFF);
    }

    // --- TCP ---
    public static long tcpSeq(byte[] p, int ihl) { return readInt(p, ihl + 4) & 0xFFFFFFFFL; }
    public static long tcpAck(byte[] p, int ihl) { return readInt(p, ihl + 8) & 0xFFFFFFFFL; }
    public static int  tcpDataOffset(byte[] p, int ihl) { return ((p[ihl + 12] >> 4) & 0x0F) * 4; }
    public static int  tcpFlags(byte[] p, int ihl) { return p[ihl + 13] & 0x3F; }
    public static int  tcpWindow(byte[] p, int ihl) {
        return ((p[ihl + 14] & 0xFF) << 8) | (p[ihl + 15] & 0xFF);
    }

    public static int readInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
             | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    public static void writeInt(byte[] b, int o, int v) {
        b[o]     = (byte) ((v >> 24) & 0xFF);
        b[o + 1] = (byte) ((v >> 16) & 0xFF);
        b[o + 2] = (byte) ((v >> 8) & 0xFF);
        b[o + 3] = (byte) (v & 0xFF);
    }

    public static void writeShort(byte[] b, int o, int v) {
        b[o]     = (byte) ((v >> 8) & 0xFF);
        b[o + 1] = (byte) (v & 0xFF);
    }

    public static String ipToString(int ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "."
             + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    public static int ipFromBytes(byte[] addr) {
        return ((addr[0] & 0xFF) << 24) | ((addr[1] & 0xFF) << 16)
             | ((addr[2] & 0xFF) << 8) | (addr[3] & 0xFF);
    }

    public static byte[] ipToBytes(int ip) {
        return new byte[] {
            (byte) ((ip >> 24) & 0xFF), (byte) ((ip >> 16) & 0xFF),
            (byte) ((ip >> 8) & 0xFF),  (byte) (ip & 0xFF)
        };
    }

    // ---------------- בנייה ----------------

    /** בונה חבילת UDP שלמה (IP header + UDP header + payload). */
    public static byte[] buildUdp(int srcIp, int srcPort, int dstIp, int dstPort,
                                  byte[] payload, int payloadLen) {
        int ipHeaderLen  = 20;
        int udpLen       = 8 + payloadLen;
        int total        = ipHeaderLen + udpLen;
        byte[] pkt = new byte[total];

        writeIpHeader(pkt, srcIp, dstIp, PROTO_UDP, total);

        writeShort(pkt, 20, srcPort);
        writeShort(pkt, 22, dstPort);
        writeShort(pkt, 24, udpLen);
        writeShort(pkt, 26, 0);                       // checksum = 0 בינתיים
        System.arraycopy(payload, 0, pkt, 28, payloadLen);

        int ck = transportChecksum(pkt, srcIp, dstIp, PROTO_UDP, 20, udpLen);
        // ב-UDP ערך 0 שמור למשמעות "אין checksum", ולכן ממירים ל-0xFFFF
        writeShort(pkt, 26, ck == 0 ? 0xFFFF : ck);
        return pkt;
    }

    /** בונה חבילת TCP שלמה. payload יכול להיות null. */
    public static byte[] buildTcp(int srcIp, int srcPort, int dstIp, int dstPort,
                                  long seq, long ack, int flags, int window,
                                  byte[] payload, int payloadOff, int payloadLen) {
        int ipHeaderLen  = 20;
        int tcpHeaderLen = 20;
        int tcpLen       = tcpHeaderLen + payloadLen;
        int total        = ipHeaderLen + tcpLen;
        byte[] pkt = new byte[total];

        writeIpHeader(pkt, srcIp, dstIp, PROTO_TCP, total);

        writeShort(pkt, 20, srcPort);
        writeShort(pkt, 22, dstPort);
        writeInt(pkt, 24, (int) seq);
        writeInt(pkt, 28, (int) ack);
        pkt[32] = (byte) ((tcpHeaderLen / 4) << 4);   // data offset, בלי אופציות
        pkt[33] = (byte) flags;
        writeShort(pkt, 34, window);
        writeShort(pkt, 36, 0);                       // checksum
        writeShort(pkt, 38, 0);                       // urgent pointer

        if (payload != null && payloadLen > 0) {
            System.arraycopy(payload, payloadOff, pkt, 40, payloadLen);
        }

        int ck = transportChecksum(pkt, srcIp, dstIp, PROTO_TCP, 20, tcpLen);
        writeShort(pkt, 36, ck);
        return pkt;
    }

    /**
     * בונה SYN/ACK הכולל אופציית MSS.
     *
     * למה זה חשוב: בלי הכרזת MSS הצד השני מניח 536 בייט לחבילה (ברירת המחדל
     * ל-non-local לפי RFC 1122). זה עובד, אבל מוריד את התפוקה בערך פי שלושה.
     * אופציית MSS מוסיפה 4 בייט לכותרת, ולכן data offset הוא 6 מילים ולא 5.
     */
    public static byte[] buildTcpWithMss(int srcIp, int srcPort, int dstIp, int dstPort,
                                         long seq, long ack, int flags, int window, int mss) {
        int ipHeaderLen  = 20;
        int tcpHeaderLen = 24;              // 20 + 4 בייט אופציה
        int tcpLen       = tcpHeaderLen;
        int total        = ipHeaderLen + tcpLen;
        byte[] pkt = new byte[total];

        writeIpHeader(pkt, srcIp, dstIp, PROTO_TCP, total);

        writeShort(pkt, 20, srcPort);
        writeShort(pkt, 22, dstPort);
        writeInt(pkt, 24, (int) seq);
        writeInt(pkt, 28, (int) ack);
        pkt[32] = (byte) ((tcpHeaderLen / 4) << 4);   // data offset = 6
        pkt[33] = (byte) flags;
        writeShort(pkt, 34, window);
        writeShort(pkt, 36, 0);                       // checksum
        writeShort(pkt, 38, 0);                       // urgent pointer

        // אופציה: kind=2 (MSS), length=4, value
        pkt[40] = 2;
        pkt[41] = 4;
        writeShort(pkt, 42, mss);

        int ck = transportChecksum(pkt, srcIp, dstIp, PROTO_TCP, 20, tcpLen);
        writeShort(pkt, 36, ck);
        return pkt;
    }

    private static void writeIpHeader(byte[] pkt, int srcIp, int dstIp, int proto, int total) {
        pkt[0] = 0x45;                    // version 4, IHL 5
        pkt[1] = 0;                       // DSCP/ECN
        writeShort(pkt, 2, total);
        writeShort(pkt, 4, 0);            // identification
        writeShort(pkt, 6, 0x4000);       // Don't Fragment
        pkt[8] = 64;                      // TTL
        pkt[9] = (byte) proto;
        writeShort(pkt, 10, 0);           // checksum
        writeInt(pkt, 12, srcIp);
        writeInt(pkt, 16, dstIp);
        writeShort(pkt, 10, checksum(pkt, 0, 20));
    }

    // ---------------- checksums ----------------

    /** checksum סטנדרטי של האינטרנט (RFC 1071). */
    public static int checksum(byte[] data, int offset, int length) {
        long sum = 0;
        int i = offset;
        while (length > 1) {
            sum += ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            i += 2;
            length -= 2;
        }
        if (length > 0) sum += (data[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    /**
     * checksum של TCP/UDP — כולל pseudo-header
     * (כתובת מקור, כתובת יעד, אפס, פרוטוקול, אורך השכבה).
     */
    private static int transportChecksum(byte[] pkt, int srcIp, int dstIp,
                                         int proto, int start, int len) {
        long sum = 0;

        sum += (srcIp >>> 16) & 0xFFFF;
        sum += srcIp & 0xFFFF;
        sum += (dstIp >>> 16) & 0xFFFF;
        sum += dstIp & 0xFFFF;
        sum += proto;
        sum += len;

        int i = start, remaining = len;
        while (remaining > 1) {
            sum += ((pkt[i] & 0xFF) << 8) | (pkt[i + 1] & 0xFF);
            i += 2;
            remaining -= 2;
        }
        if (remaining > 0) sum += (pkt[i] & 0xFF) << 8;

        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }
}
