package com.magen.family.service.vpn;

import org.junit.Test;
import static org.junit.Assert.*;

public class TcpSeqTest {
    @Test public void unwrapsAcross32BitBoundary() {
        assertEquals(0x100000010L, TcpSeq.unwrap(0x00000010L, 0xfffffff0L));
        assertEquals(0xfffffff0L, TcpSeq.unwrap(0xfffffff0L, 0x100000010L));
    }

    @Test public void acknowledgesAcross32BitBoundaryButNotBeyondSentData() {
        assertTrue(TcpSeq.acknowledges(0x00000020L, 0xfffffff0L, 0x100000020L));
        assertFalse(TcpSeq.acknowledges(0x00000030L, 0xfffffff0L, 0x100000020L));
    }
}
