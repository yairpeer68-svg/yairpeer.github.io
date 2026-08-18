package com.magen.family.filter;

import org.junit.Test;
import static org.junit.Assert.*;

public class HostUtilTest {
    @Test public void extractsUrlsAndPorts() {
        assertEquals("example.com", HostUtil.extractHost("https://www.Example.com:8443/a?q=1"));
        assertEquals("example.com", HostUtil.extractHost("example.com:443/path"));
    }

    @Test public void keepsIpv6Intact() {
        assertEquals("2001:db8::1", HostUtil.normalizeHost("[2001:db8::1]:443"));
        assertEquals("2001:db8::1", HostUtil.normalizeHost("2001:db8::1"));
    }

    @Test public void normalizesIdnAndTrailingDot() {
        assertEquals("xn--5dbe4d.com", HostUtil.normalizeHost("בדק.com."));
    }
}
