package com.magen.family.service.vpn;

import android.os.SystemClock;
import android.util.Log;

import com.magen.family.filter.DomainVerdict;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * UdpRelay — העברת UDP במרחב המשתמש + סינון DNS.
 *
 * למה זה חייב להתקיים:
 *   בגרסה הקודמת ה-VPN ניתב לתוך המנהרה רק ~40 כתובות IP של ספקי DNS ידועים.
 *   שאילתה ל-resolver אחר (כל IP בעולם) פשוט לא נכנסה למנהרה — הסינון בכלל
 *   לא ראה אותה. עכשיו כשמנתבים 0.0.0.0/0, *כל* חבילה עוברת כאן, ולכן צריך
 *   להעביר בעצמנו את מה שלא חוסמים.
 *
 * מדיניות:
 *   • פורט 53  — מיירטים. שם חסום -> NXDOMAIN מקומי.  שם מותר -> מועבר
 *                 ל-upstream *שלנו* בלי קשר ליעד המקורי. כך גם אם מישהו
 *                 הגדיר resolver פרטי, הוא בפועל מקבל תשובות מהמסנן שלנו.
 *   • פורט 853 — DoT. נזרק.
 *   • פורט 443 — QUIC. נזרק כדי לאלץ נפילה חזרה ל-TCP, שם אפשר לקרוא SNI.
 *   • השאר     — NAT רגיל.
 */
public class UdpRelay {

    private static final String TAG = "UdpRelay";

    private static final int  BUFFER_SIZE       = 32767;
    private static final long DNS_TIMEOUT_MS    = 15_000L;
    private static final long SESSION_TIMEOUT_MS = 120_000L;
    private static final long CLEANUP_EVERY_MS  = 15_000L;

    private final TunBridge bridge;
    private final Selector selector;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Session> pendingRegistration = new ConcurrentLinkedQueue<>();

    private volatile boolean running = false;
    private Thread selectorThread;
    private long lastCleanup = 0;

    public UdpRelay(TunBridge bridge) throws IOException {
        this.bridge = bridge;
        this.selector = Selector.open();
    }

    private static class Session {
        DatagramChannel channel;
        int deviceIp, devicePort;
        int remoteIp, remotePort;      // היעד כפי שהמכשיר רואה אותו
        boolean isDns;
        volatile long lastUse;
        String key;
    }

    public void start() {
        running = true;
        selectorThread = new Thread(this::selectorLoop, "MagenUdpRelay");
        selectorThread.setDaemon(true);
        selectorThread.start();
    }

    public void stop() {
        running = false;
        try { selector.wakeup(); } catch (Exception ignored) {}
        for (Session s : sessions.values()) closeSession(s);
        sessions.clear();
        try { selector.close(); } catch (Exception ignored) {}
        if (selectorThread != null) selectorThread.interrupt();
    }

    // ---------------- מהמכשיר החוצה ----------------

    /** מקבל חבילת IPv4/UDP מה-TUN. */
    public void handleOutbound(byte[] packet, int length) {
        if (packet == null || length < 28 || length > packet.length) return;
        int ihl        = Ipv4.ihl(packet);
        int deviceIp   = Ipv4.srcIp(packet);
        int remoteIp   = Ipv4.dstIp(packet);
        int devicePort = Ipv4.srcPort(packet, ihl);
        int remotePort = Ipv4.dstPort(packet, ihl);

        if (ihl < 20 || ihl > length - 8) return;
        int udpLen = ((packet[ihl + 4] & 0xFF) << 8) | (packet[ihl + 5] & 0xFF);
        if (udpLen < 8 || udpLen > length - ihl) return;
        int payloadOff = ihl + 8;
        int payloadLen = udpLen - 8;

        // DoT — אין דרך לראות מה בפנים, חוסמים לכל יעד
        if (remotePort == 853) {
            VpnStats.countBlockedDot();
            return;
        }

        // QUIC — זורקים כדי לאלץ נפילה ל-TCP, שם ה-SNI גלוי
        if (remotePort == 443 && VpnPolicy.blockQuic()) {
            VpnStats.countBlockedQuic();
            return;
        }

        if (remotePort == 53) {
            handleDns(packet, payloadOff, payloadLen, deviceIp, devicePort, remoteIp, remotePort);
            return;
        }

        forward(packet, payloadOff, payloadLen,
                deviceIp, devicePort, remoteIp, remotePort, false);
    }

    private void handleDns(byte[] packet, int payloadOff, int payloadLen,
                           int deviceIp, int devicePort, int remoteIp, int remotePort) {
        String qname = DnsMessage.extractQueryName(packet, payloadOff, payloadLen);

        // חסימת ECH — שאילתת HTTPS/SVCB (type 65) נענית ב-NODATA כדי שה-SNI
        // יישאר גלוי ולא יוצפן. בלי זה סינון ה-SNI היה נשבר בעתיד.
        if (VpnPolicy.blockEch() && DnsMessage.queryType(packet, payloadOff, payloadLen) == 65) {
            byte[] nd = DnsMessage.buildNoData(packet, payloadOff, payloadLen);
            if (nd != null) {
                byte[] reply = Ipv4.buildUdp(remoteIp, remotePort, deviceIp, devicePort,
                                             nd, nd.length);
                bridge.writeToTun(reply, reply.length);
                return;
            }
        }

        // כפיית Safe Search / YouTube Restricted — מפנים למנוע החיפוש הבטוח
        if (qname != null) {
            byte[] forced = com.magen.family.filter.SafeSearchEnforcer
                .forcedIp(bridge.context(), qname);
            if (forced != null) {
                byte[] a = DnsMessage.buildAResponse(packet, payloadOff, payloadLen, forced);
                if (a != null) {
                    byte[] reply = Ipv4.buildUdp(remoteIp, remotePort, deviceIp, devicePort,
                                                 a, a.length);
                    bridge.writeToTun(reply, reply.length);
                    return;
                }
            }
        }

        if (qname != null && DomainVerdict.isBlocked(bridge.context(), qname)) {
            byte[] nx = DnsMessage.buildNxDomain(packet, payloadOff, payloadLen);
            if (nx != null) {
                // התשובה חייבת להיראות כאילו הגיעה מהיעד שהמכשיר פנה אליו
                byte[] reply = Ipv4.buildUdp(remoteIp, remotePort, deviceIp, devicePort,
                                             nx, nx.length);
                bridge.writeToTun(reply, reply.length);
                VpnStats.countBlockedDomain(qname);
                Log.d(TAG, "NXDOMAIN " + qname);
            }
            return;
        }

        // מותר — מעבירים ל-upstream שלנו, לא ליעד שהמכשיר ביקש
        forward(packet, payloadOff, payloadLen,
                deviceIp, devicePort, remoteIp, remotePort, true);
    }

    private void forward(byte[] packet, int payloadOff, int payloadLen,
                         int deviceIp, int devicePort,
                         int remoteIp, int remotePort, boolean isDns) {
        String key = devicePort + ">" + remoteIp + ":" + remotePort;
        Session session = sessions.get(key);

        if (session == null) {
            session = openSession(key, deviceIp, devicePort, remoteIp, remotePort, isDns);
            if (session == null) return;
        }
        session.lastUse = SystemClock.elapsedRealtime();

        try {
            ByteBuffer buf = ByteBuffer.wrap(packet, payloadOff, payloadLen);
            // Each channel is connected to exactly one peer in openSession(). Besides simplifying
            // writes, this makes the kernel discard UDP datagrams from every other source.
            int written = session.channel.write(buf);
            if (written != payloadLen) {
                // Non-blocking UDP may return zero under local send-buffer pressure. A datagram
                // cannot be partially queued safely, so drop this packet and let UDP/DNS retry.
                Log.d(TAG, "udp local send buffer busy; datagram dropped");
            }
        } catch (Exception e) {
            Log.w(TAG, "udp send failed: " + e.getMessage());
            closeSession(session);
            sessions.remove(key);
        }
    }

    private Session openSession(String key, int deviceIp, int devicePort,
                                int remoteIp, int remotePort, boolean isDns) {
        try {
            DatagramChannel ch = DatagramChannel.open();
            ch.configureBlocking(false);

            // בלי protect() החבילה תחזור לתוך המנהרה ותיצור לולאה אינסופית
            if (!bridge.protect(ch.socket())) {
                Log.e(TAG, "protect() failed for udp socket");
                ch.close();
                return null;
            }

            // Connect after VpnService.protect(). DNS sessions connect to Magen's filtered
            // resolver; ordinary UDP sessions connect to the original destination. A connected
            // DatagramChannel accepts inbound packets only from this exact peer.
            InetSocketAddress target = isDns
                ? new InetSocketAddress(VpnPolicy.upstreamDns(), 53)
                : new InetSocketAddress(
                    java.net.InetAddress.getByAddress(Ipv4.ipToBytes(remoteIp)), remotePort);
            ch.connect(target);

            Session s = new Session();
            s.channel    = ch;
            s.deviceIp   = deviceIp;
            s.devicePort = devicePort;
            s.remoteIp   = remoteIp;
            s.remotePort = remotePort;
            s.isDns      = isDns;
            s.lastUse    = SystemClock.elapsedRealtime();
            s.key        = key;

            sessions.put(key, s);
            pendingRegistration.add(s);
            selector.wakeup();
            return s;
        } catch (Exception e) {
            Log.e(TAG, "openSession failed: " + e.getMessage());
            return null;
        }
    }

    // ---------------- מהאינטרנט פנימה ----------------

    private void selectorLoop() {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);

        while (running) {
            try {
                registerPending();
                selector.select(1000);

                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid() || !key.isReadable()) continue;

                    Session s = (Session) key.attachment();
                    readResponse(s, buffer);
                }

                cleanupIdle();
            } catch (Exception e) {
                if (running) Log.e(TAG, "selector loop: " + e.getMessage());
            }
        }
    }

    private void registerPending() {
        Session s;
        while ((s = pendingRegistration.poll()) != null) {
            try {
                s.channel.register(selector, SelectionKey.OP_READ, s);
            } catch (Exception e) {
                Log.w(TAG, "register failed: " + e.getMessage());
                closeSession(s);
                sessions.remove(s.key);
            }
        }
    }

    private void readResponse(Session s, ByteBuffer buffer) {
        try {
            buffer.clear();
            int received = s.channel.read(buffer);
            if (received <= 0) return;
            buffer.flip();

            int len = buffer.remaining();
            byte[] payload = new byte[len];
            buffer.get(payload);

            s.lastUse = SystemClock.elapsedRealtime();

            // מזייפים את המקור ככתובת שהמכשיר פנה אליה, אחרת הוא ידחה את התשובה
            byte[] reply = Ipv4.buildUdp(s.remoteIp, s.remotePort,
                                         s.deviceIp, s.devicePort,
                                         payload, len);
            bridge.writeToTun(reply, reply.length);
        } catch (Exception e) {
            Log.w(TAG, "readResponse: " + e.getMessage());
            closeSession(s);
            sessions.remove(s.key);
        }
    }

    private void cleanupIdle() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCleanup < CLEANUP_EVERY_MS) return;
        lastCleanup = now;

        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            long timeout = s.isDns ? DNS_TIMEOUT_MS : SESSION_TIMEOUT_MS;
            if (now - s.lastUse > timeout) {
                closeSession(s);
                sessions.remove(e.getKey());
            }
        }
    }

    private void closeSession(Session s) {
        if (s == null) return;
        try { s.channel.close(); } catch (Exception ignored) {}
    }

    public int activeSessions() {
        return sessions.size();
    }
}
