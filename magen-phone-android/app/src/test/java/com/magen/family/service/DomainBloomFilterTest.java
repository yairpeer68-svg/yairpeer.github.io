package com.magen.family.service;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import static org.junit.Assert.*;

public class DomainBloomFilterTest {
    @Test public void roundTripPreservesMembership() throws Exception {
        DomainBloomFilter f = new DomainBloomFilter(1000, 0.01);
        f.add("bad.example");
        f.add("adult.example");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        f.writeTo(out);
        DomainBloomFilter restored = DomainBloomFilter.readFrom(
            new ByteArrayInputStream(out.toByteArray()));
        assertEquals(2, restored.getItemCount());
        assertTrue(restored.mightContain("bad.example"));
        assertTrue(restored.isBlockedHost("x.adult.example"));
    }

    @Test public void corruptedChecksumIsRejected() throws Exception {
        DomainBloomFilter f = new DomainBloomFilter(1000, 0.01);
        f.add("bad.example");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        f.writeTo(out);
        byte[] bytes = out.toByteArray();
        bytes[12] ^= 0x01;
        try {
            DomainBloomFilter.readFrom(new ByteArrayInputStream(bytes));
            fail("corruption should be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("checksum"));
        }
    }

    @Test public void maliciousLegacyHeaderIsRejectedBeforeAllocation() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeInt(0x4D424C4D); // MBLM v1
        d.writeInt(Integer.MAX_VALUE);
        d.writeInt(12);
        d.writeInt(1);
        d.writeInt(Integer.MAX_VALUE);
        d.flush();
        try {
            DomainBloomFilter.readFrom(new ByteArrayInputStream(out.toByteArray()));
            fail("oversized bloom header should be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("size"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidConstructorInput() {
        new DomainBloomFilter(0, 0.01);
    }
}
