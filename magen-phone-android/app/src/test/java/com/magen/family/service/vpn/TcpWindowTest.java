package com.magen.family.service.vpn;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TcpWindowTest {
    @Test public void zeroWindowStopsReading() {
        assertEquals(0, TcpWindow.available(1000, 900, 0));
    }

    @Test public void availableSubtractsInflight() {
        assertEquals(900, TcpWindow.available(1100, 1000, 1000));
    }

    @Test public void scaleIsAppliedAndCapped() {
        assertEquals(65535 << 2, TcpWindow.scale(65535, 2));
        assertEquals(65535 << 14, TcpWindow.scale(65535, 99));
    }

    @Test public void parsesWindowScaleOption() {
        byte[] packet = new byte[60];
        int ihl = 20;
        // TCP options: NOP, Window Scale(kind=3,len=3,value=7), EOL
        packet[ihl + 20] = 1;
        packet[ihl + 21] = 3;
        packet[ihl + 22] = 3;
        packet[ihl + 23] = 7;
        packet[ihl + 24] = 0;
        assertEquals(7, TcpWindow.parseWindowScale(packet, ihl, 28));
    }
}
