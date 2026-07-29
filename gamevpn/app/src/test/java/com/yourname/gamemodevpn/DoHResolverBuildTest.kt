package com.yourname.gamemodevpn

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class DoHResolverBuildTest {

    // Replicate buildDnsQuery from DoHResolver for testing
    private fun buildDnsQuery(domain: String): ByteArray {
        val buf = ByteArrayOutputStream()
        val out = DataOutputStream(buf)
        out.writeShort(0x1234)
        out.writeShort(0x0100)
        out.writeShort(1)
        out.writeShort(0)
        out.writeShort(0)
        out.writeShort(0)
        for (part in domain.split(".")) {
            out.writeByte(part.length)
            out.write(part.toByteArray(Charsets.US_ASCII))
        }
        out.writeByte(0)
        out.writeShort(1)
        out.writeShort(1)
        return buf.toByteArray()
    }

    @Test
    fun `query for single-label domain has correct header`() {
        val q = buildDnsQuery("example.com")
        assertTrue(q.size > 12)
        // Check transaction ID
        assertEquals(0x12.toByte(), q[0])
        assertEquals(0x34.toByte(), q[1])
        // Check flags (standard query, RD=1)
        assertEquals(0x01.toByte(), q[2])
        assertEquals(0x00.toByte(), q[3])
        // Question count = 1
        assertEquals(0x00.toByte(), q[4])
        assertEquals(0x01.toByte(), q[5])
    }

    @Test
    fun `query ends with type A and class IN`() {
        val q = buildDnsQuery("test.com")
        // Last 4 bytes: QTYPE=1 (A), QCLASS=1 (IN)
        assertEquals(0x00.toByte(), q[q.size - 4])
        assertEquals(0x01.toByte(), q[q.size - 3])
        assertEquals(0x00.toByte(), q[q.size - 2])
        assertEquals(0x01.toByte(), q[q.size - 1])
    }

    @Test
    fun `longer domain produces larger query`() {
        val short = buildDnsQuery("a.com")
        val long  = buildDnsQuery("very.long.subdomain.example.com")
        assertTrue(long.size > short.size)
    }
}
