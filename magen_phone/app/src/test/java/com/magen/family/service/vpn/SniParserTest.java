package com.magen.family.service.vpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * בדיקות ל-SniParser — חילוץ שם המארח מתעבורת TCP.
 * ה-TLS ClientHello נבנה ידנית כדי לבדוק את הפרסור בלי תלות ברשת.
 */
public class SniParserTest {

    @Test
    public void extractHttpHost_fromGetRequest() {
        String req = "GET /path HTTP/1.1\r\nHost: example.com\r\nUser-Agent: x\r\n\r\n";
        byte[] buf = req.getBytes();
        assertEquals("example.com", SniParser.extractHost(buf, buf.length));
    }

    @Test
    public void extractHttpHost_stripsPort() {
        String req = "GET / HTTP/1.1\r\nHost: example.com:8080\r\n\r\n";
        byte[] buf = req.getBytes();
        assertEquals("example.com", SniParser.extractHttpHost(buf, buf.length));
    }

    @Test
    public void nonHttp_returnsNull() {
        byte[] buf = "random bytes here".getBytes();
        assertNull(SniParser.extractHttpHost(buf, buf.length));
    }

    @Test
    public void mayContainHost_detectsTlsAndHttp() {
        byte[] tls = new byte[]{ 0x16, 0x03, 0x01, 0, 0 };
        assertTrue(SniParser.mayContainHost(tls, tls.length));
        byte[] http = "GET / HT".getBytes();
        assertTrue(SniParser.mayContainHost(http, http.length));
    }

    @Test
    public void extractTlsSni_fromCraftedClientHello() {
        byte[] hello = craftClientHello("bad-site.com");
        assertEquals("bad-site.com", SniParser.extractTlsSni(hello, hello.length));
    }

    /**
     * בונה ClientHello מינימלי עם server_name extension יחיד.
     * מספיק כדי לבדוק את הפרסור (לא נשלח לרשת).
     */
    private static byte[] craftClientHello(String host) {
        byte[] hb = host.getBytes();
        java.io.ByteArrayOutputStream ext = new java.io.ByteArrayOutputStream();
        // server_name extension body
        int nameLen = hb.length;
        int listLen = nameLen + 3;             // type(1) + len(2) + name
        ext.write(0x00); ext.write(0x00);      // extension type = server_name
        ext.write((listLen + 2) >> 8); ext.write((listLen + 2) & 0xFF); // ext length
        ext.write(listLen >> 8); ext.write(listLen & 0xFF);             // server name list length
        ext.write(0x00);                       // name type host_name
        ext.write(nameLen >> 8); ext.write(nameLen & 0xFF);
        ext.write(hb, 0, hb.length);
        byte[] extensions = ext.toByteArray();

        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(0x03); body.write(0x03);    // client version
        for (int i = 0; i < 32; i++) body.write(0); // random
        body.write(0x00);                      // session id length 0
        body.write(0x00); body.write(0x02);    // cipher suites length 2
        body.write(0x00); body.write(0x2f);    // one cipher suite
        body.write(0x01);                      // compression methods length
        body.write(0x00);                      // null compression
        body.write(extensions.length >> 8); body.write(extensions.length & 0xFF);
        body.write(extensions, 0, extensions.length);
        byte[] hs = body.toByteArray();

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0x16);                       // handshake record
        out.write(0x03); out.write(0x01);      // record version
        int recLen = hs.length + 4;
        out.write(recLen >> 8); out.write(recLen & 0xFF);
        out.write(0x01);                       // handshake type = ClientHello
        out.write((hs.length >> 16) & 0xFF);
        out.write((hs.length >> 8) & 0xFF);
        out.write(hs.length & 0xFF);
        out.write(hs, 0, hs.length);
        return out.toByteArray();
    }
}
