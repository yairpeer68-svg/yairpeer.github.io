package com.magen.family.service.vpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * בדיקות ל-DnsMessage — פרסור QNAME/QTYPE ובניית NXDOMAIN. אלה הלב של
 * חסימת ה-DNS: טעות בפרסור = דומיינים אסורים עוברים, או תקינים נחסמים.
 */
public class DnsMessageTest {

    /** שאילתת DNS ל-"example.com" מסוג A (QTYPE=1). */
    private static byte[] queryExampleComA() {
        return new byte[] {
            0x12, 0x34,             // ID
            0x01, 0x00,             // flags: standard query, RD=1
            0x00, 0x01,             // QDCOUNT=1
            0x00, 0x00,             // ANCOUNT=0
            0x00, 0x00,             // NSCOUNT=0
            0x00, 0x00,             // ARCOUNT=0
            0x07, 'e','x','a','m','p','l','e',
            0x03, 'c','o','m',
            0x00,                   // סוף השם
            0x00, 0x01,             // QTYPE=A
            0x00, 0x01              // QCLASS=IN
        };
    }

    @Test
    public void extractsQueryName() {
        byte[] q = queryExampleComA();
        assertEquals("example.com", DnsMessage.extractQueryName(q, 0, q.length));
    }

    @Test
    public void readsQueryTypeA() {
        byte[] q = queryExampleComA();
        assertEquals(1, DnsMessage.queryType(q, 0, q.length));
    }

    @Test
    public void responsePacketIsRejectedAsQuery() {
        // אותה חבילה אבל עם QR=1 (תשובה) — extractQueryName חייב להחזיר null
        byte[] q = queryExampleComA();
        q[2] = (byte) 0x81;   // מדליק את ביט ה-QR
        assertNull(DnsMessage.extractQueryName(q, 0, q.length));
    }

    @Test
    public void nxDomainKeepsIdSetsQrAndRcode3() {
        byte[] q = queryExampleComA();
        byte[] r = DnsMessage.buildNxDomain(q, 0, q.length);
        assertNotNull(r);
        // ID נשמר
        assertEquals(q[0], r[0]);
        assertEquals(q[1], r[1]);
        // QR=1 (ביט 15 של flags)
        assertTrue((r[2] & 0x80) != 0);
        // RCODE=3 (NXDOMAIN) בארבעת הביטים התחתונים של flags
        assertEquals(3, r[3] & 0x0F);
        // אפס answers
        assertEquals(0, ((r[6] & 0xFF) << 8) | (r[7] & 0xFF));
    }

    @Test
    public void garbageInputIsSafe() {
        byte[] tooShort = new byte[] { 0x00, 0x01, 0x02 };
        assertNull(DnsMessage.extractQueryName(tooShort, 0, tooShort.length));
        assertEquals(-1, DnsMessage.queryType(tooShort, 0, tooShort.length));
    }
}
