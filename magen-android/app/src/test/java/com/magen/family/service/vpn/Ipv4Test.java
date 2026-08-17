package com.magen.family.service.vpn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * בדיקות לפירוק/בנייה של חבילות IPv4 — הבסיס של מנוע ה-VPN.
 */
public class Ipv4Test {

    @Test
    public void ipBytes_roundTrip() {
        int ip = Ipv4.ipFromBytes(new byte[]{ (byte)216, (byte)239, 38, 120 });
        assertEquals("216.239.38.120", Ipv4.ipToString(ip));
        byte[] back = Ipv4.ipToBytes(ip);
        assertEquals((byte)216, back[0]);
        assertEquals((byte)239, back[1]);
        assertEquals(38, back[2]);
        assertEquals(120, back[3]);
    }

    @Test
    public void intReadWrite_roundTrip() {
        byte[] buf = new byte[8];
        Ipv4.writeInt(buf, 2, 0x12345678);
        assertEquals(0x12345678, Ipv4.readInt(buf, 2));
    }

    @Test
    public void shortWrite_isBigEndian() {
        byte[] buf = new byte[4];
        Ipv4.writeShort(buf, 0, 0x0ABC);
        assertEquals(0x0A, buf[0] & 0xFF);
        assertEquals(0xBC, buf[1] & 0xFF);
    }

    @Test
    public void checksum_ofZeroBuffer_isAllOnes() {
        // סכום ריק → המשלים הוא 0xFFFF
        byte[] buf = new byte[20];
        assertEquals(0xFFFF, Ipv4.checksum(buf, 0, 20));
    }

    @Test
    public void buildUdp_hasValidHeaderFields() {
        int src = Ipv4.ipFromBytes(new byte[]{10, 7, 7, 2});
        int dst = Ipv4.ipFromBytes(new byte[]{ (byte)94, (byte)140, 14, 15 });
        byte[] payload = { 1, 2, 3, 4 };
        byte[] pkt = Ipv4.buildUdp(dst, 53, src, 40000, payload, payload.length);

        assertEquals(0x45, pkt[0] & 0xFF);                       // version 4, IHL 5
        assertEquals(Ipv4.PROTO_UDP, pkt[9] & 0xFF);             // protocol
        assertEquals(20 + 8 + 4, Ipv4.totalLength(pkt));         // total length
        assertEquals(53, Ipv4.srcPort(pkt, 20));                 // UDP src port
        assertEquals(40000, Ipv4.dstPort(pkt, 20));              // UDP dst port
        // ה-IP checksum צריך לאמת ל-0 (מעל כותרת תקינה כולל שדה ה-checksum)
        assertEquals(0, Ipv4.checksum(pkt, 0, 20));
    }
}
