package com.magen.family.service.vpn;

import android.util.Log;

import com.magen.family.filter.DomainVerdict;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * TcpRelay — מכונת מצבים מינימלית של TCP במרחב המשתמש.
 *
 * מה זה עושה:
 *   ה-TUN מספק חבילות IP גולמיות. כדי לאפשר תעבורת TCP רגילה תוך כדי
 *   *הצצה* לתחילת החיבור (כדי לקרוא SNI), אנחנו מסיימים את חיבור ה-TCP מול
 *   המכשיר בעצמנו, ופותחים במקביל חיבור אמיתי אל היעד. במילים אחרות:
 *   proxy שקוף ברמת החבילה.
 *
 * שתי הפשטות מכוונות שמקטינות מאוד את הסיכון:
 *
 *   1. אין שידור חוזר (retransmission) לכיוון המכשיר.
 *      כתיבה ל-file descriptor של ה-TUN היא מסירה מקומית לקרנל — אין בה
 *      איבוד חבילות. איבוד אמיתי קורה רק בצד האינטרנט, ושם ה-TCP של הקרנל
 *      (דרך SocketChannel) כבר מטפל בזה. לכן מימוש שידור חוזר כאן היה קוד
 *      מסובך שלא מוסיף אמינות.
 *
 *   2. אין טיפול בחבילות מחוץ לסדר.
 *      מאותה סיבה — הקרנל מוסר לנו את החבילות בסדר. יש בכל זאת בדיקת סדר
 *      כרשת ביטחון: חבילה לא בסדר נענית ב-ACK חוזר, מה שגורם לשולח לשדר שוב.
 *
 * בקרת זרימה כן ממומשת (חלון המכשיר), אחרת הורדה גדולה תציף את הצד השני.
 */
public class TcpRelay {

    private static final String TAG = "TcpRelay";

    private static final int MSS               = 1400;
    private static final int RECV_BUFFER       = 16384;
    private static final int INSPECT_LIMIT     = 2048;   // מספיק ל-ClientHello
    private static final int DEVICE_WINDOW     = 65535;
    private static final int MAX_PENDING_UPSTREAM_BYTES = 512 * 1024;
    private static final long IDLE_TIMEOUT_MS  = 300_000L;
    private static final long CLEANUP_EVERY_MS = 30_000L;

    private enum State { CONNECTING, ESTABLISHED, CLOSING, CLOSED }

    private final TunBridge bridge;
    private final Selector selector;
    private final Random random = new Random();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Connection> pendingRegistration = new ConcurrentLinkedQueue<>();

    private volatile boolean running = false;
    private Thread selectorThread;
    private long lastCleanup = 0;

    public TcpRelay(TunBridge bridge) throws IOException {
        this.bridge = bridge;
        this.selector = Selector.open();
    }

    private class Connection {
        String key;
        SocketChannel channel;
        SelectionKey selectionKey;

        int deviceIp, devicePort, remoteIp, remotePort;

        long mySeq;          // מספר הרצף הבא שנשלח למכשיר
        long theirSeq;       // מספר הרצף הבא שאנחנו מצפים מהמכשיר
        long myUnacked;      // הבייט הראשון שעדיין לא אושר על ידי המכשיר
        int  deviceWindow = DEVICE_WINDOW;

        volatile State state = State.CONNECTING;
        volatile boolean upstreamConnected = false;

        /** בייטים ראשונים מהמכשיר, לצורך חילוץ SNI/Host. */
        ByteBuffer inspect = ByteBuffer.allocate(INSPECT_LIMIT);
        boolean verdictResolved = false;

        /** נתונים שממתינים עד שהחיבור ליעד יושלם. */
        final ArrayDeque<byte[]> pendingUpstream = new ArrayDeque<>();
        int pendingUpstreamBytes = 0;

        volatile long lastActive = System.currentTimeMillis();

        /** כמה בייטים בדרך למכשיר שעדיין לא אושרו. */
        long inFlight() { return mySeq - myUnacked; }

        boolean windowFull() { return inFlight() >= Math.max(deviceWindow, MSS); }
    }

    public void start() {
        running = true;
        selectorThread = new Thread(this::selectorLoop, "MagenTcpRelay");
        selectorThread.setDaemon(true);
        selectorThread.start();
    }

    public void stop() {
        running = false;
        try { selector.wakeup(); } catch (Exception ignored) {}
        for (Connection c : connections.values()) closeConnection(c, false);
        connections.clear();
        try { selector.close(); } catch (Exception ignored) {}
        if (selectorThread != null) selectorThread.interrupt();
    }

    // ================= מהמכשיר החוצה =================

    public void handleOutbound(byte[] packet, int length) {
        int ihl        = Ipv4.ihl(packet);
        int deviceIp   = Ipv4.srcIp(packet);
        int remoteIp   = Ipv4.dstIp(packet);
        int devicePort = Ipv4.srcPort(packet, ihl);
        int remotePort = Ipv4.dstPort(packet, ihl);

        long seq   = Ipv4.tcpSeq(packet, ihl);
        long ack   = Ipv4.tcpAck(packet, ihl);
        int  flags = Ipv4.tcpFlags(packet, ihl);
        int  window = Ipv4.tcpWindow(packet, ihl);

        int dataOffset = Ipv4.tcpDataOffset(packet, ihl);
        int payloadOff = ihl + dataOffset;
        int payloadLen = length - payloadOff;
        if (payloadLen < 0) payloadLen = 0;

        // DNS-over-TCP ו-DoT — נזרקים לכל יעד
        if (remotePort == 53) { VpnStats.countBlockedDot(); sendRst(deviceIp, devicePort, remoteIp, remotePort, seq + 1); return; }
        if (remotePort == 853) { VpnStats.countBlockedDot(); sendRst(deviceIp, devicePort, remoteIp, remotePort, seq + 1); return; }

        String key = devicePort + ">" + remoteIp + ":" + remotePort;
        Connection conn = connections.get(key);

        // --- SYN: חיבור חדש ---
        if ((flags & Ipv4.SYN) != 0 && (flags & Ipv4.ACK) == 0) {
            if (conn != null) return;                 // SYN חוזר — מתעלמים
            openConnection(key, deviceIp, devicePort, remoteIp, remotePort, seq, window);
            return;
        }

        if (conn == null) {
            // חבילה לחיבור שלא מוכר — מודיעים לשולח שאין חיבור
            sendRst(deviceIp, devicePort, remoteIp, remotePort, seq);
            return;
        }

        conn.lastActive = System.currentTimeMillis();
        conn.deviceWindow = window > 0 ? window : DEVICE_WINDOW;

        if ((flags & Ipv4.RST) != 0) {
            closeConnection(conn, false);
            return;
        }

        if ((flags & Ipv4.ACK) != 0) {
            if (ack > conn.myUnacked) conn.myUnacked = ack;
            if (conn.state == State.CONNECTING && conn.upstreamConnected) {
                conn.state = State.ESTABLISHED;
            }
            // התפנה מקום בחלון — אפשר לחדש קריאה מהאינטרנט
            if (!conn.windowFull()) resumeReading(conn);
        }

        if (payloadLen > 0) {
            if (seq != conn.theirSeq) {
                // כפילות או מחוץ לסדר — ACK חוזר מבקש מהשולח לשדר שוב
                sendAck(conn);
                return;
            }
            conn.theirSeq += payloadLen;
            // אם החיבור נחסם (SNI) כבר נשלח RST — אסור לשלוח אחריו ACK
            if (!handleDeviceData(conn, packet, payloadOff, payloadLen)) return;
            sendAck(conn);
        }

        if ((flags & Ipv4.FIN) != 0) {
            conn.theirSeq++;                          // FIN תופס מספר רצף
            sendAck(conn);
            conn.state = State.CLOSING;
            try {
                if (conn.channel != null && conn.channel.isConnected()) {
                    conn.channel.shutdownOutput();
                }
            } catch (Exception ignored) {}
        }
    }

    private void openConnection(String key, int deviceIp, int devicePort,
                                int remoteIp, int remotePort, long seq, int window) {
        Connection conn = new Connection();
        conn.key        = key;
        conn.deviceIp   = deviceIp;
        conn.devicePort = devicePort;
        conn.remoteIp   = remoteIp;
        conn.remotePort = remotePort;
        conn.theirSeq   = seq + 1;                    // SYN תופס מספר רצף
        conn.mySeq      = random.nextInt(Integer.MAX_VALUE) & 0xFFFFFFFFL;
        conn.myUnacked  = conn.mySeq;
        conn.deviceWindow = window > 0 ? window : DEVICE_WINDOW;

        try {
            SocketChannel ch = SocketChannel.open();
            ch.configureBlocking(false);

            // בלי protect() החיבור יחזור לתוך המנהרה — לולאה אינסופית
            if (!bridge.protect(ch.socket())) {
                Log.e(TAG, "protect() failed for tcp socket");
                ch.close();
                sendRst(deviceIp, devicePort, remoteIp, remotePort, conn.theirSeq);
                return;
            }

            conn.channel = ch;
            connections.put(key, conn);

            ch.connect(new InetSocketAddress(
                InetAddress.getByAddress(Ipv4.ipToBytes(remoteIp)), remotePort));

            pendingRegistration.add(conn);
            selector.wakeup();
        } catch (Exception e) {
            Log.w(TAG, "connect failed " + Ipv4.ipToString(remoteIp) + ":" + remotePort
                     + " — " + e.getMessage());
            connections.remove(key);
            sendRst(deviceIp, devicePort, remoteIp, remotePort, conn.theirSeq);
        }
    }

    /**
     * נתונים מהמכשיר: בודקים SNI אם עוד לא הכרענו, אחרת מעבירים ישירות.
     *
     * @return false אם החיבור נסגר (נחסם) ואין לשלוח עליו יותר כלום.
     */
    private boolean handleDeviceData(Connection conn, byte[] packet, int off, int len) {
        if (!conn.verdictResolved && VpnPolicy.sniFilter() && isInspectablePort(conn.remotePort)) {
            int room = conn.inspect.remaining();
            int copy = Math.min(room, len);
            conn.inspect.put(packet, off, copy);

            byte[] snapshot = conn.inspect.array();
            int snapshotLen = conn.inspect.position();

            String host = SniParser.extractHost(snapshot, snapshotLen);
            if (host != null) {
                conn.verdictResolved = true;
                if (DomainVerdict.isBlocked(bridge.context(), host)) {
                    Log.d(TAG, "SNI block " + host);
                    VpnStats.countBlockedSni(host);
                    // RST מיידי — הדפדפן מציג שגיאת חיבור מיד, בלי המתנה
                    sendRst(conn.deviceIp, conn.devicePort,
                            conn.remoteIp, conn.remotePort, conn.mySeq);
                    // חייב להיות removeFromMap=true, אחרת חבילות המשך של
                    // אותו חיבור ימצאו רשומה סגורה וינסו לכתוב ל-channel סגור
                    closeConnection(conn, true);
                    return false;
                }
                flushInspectBuffer(conn, snapshot, snapshotLen);
            } else if (conn.inspect.remaining() == 0
                    || !SniParser.mayContainHost(snapshot, snapshotLen)
                    || SniParser.isTlsRecordComplete(snapshot, snapshotLen)) {
                // או שנגמרה המכסה, או שזה בכלל לא TLS/HTTP, או שה-ClientHello
                // התקבל במלואו ואין בו SNI. בכל אחד מהמקרים אין טעם להמתין —
                // ממשיכים בלי לחסום. בלי הבדיקה הזו החיבור היה נתקע לנצח.
                conn.verdictResolved = true;
                flushInspectBuffer(conn, snapshot, snapshotLen);
            }
            // אם נשאר מקום ועדיין ייתכן שם — מחכים לחבילה הבאה

            // בייטים שלא נכנסו למכסת הבדיקה נשלחים כרגיל
            if (copy < len && conn.verdictResolved) {
                writeUpstream(conn, packet, off + copy, len - copy);
            }
            return true;
        }

        writeUpstream(conn, packet, off, len);
        return true;
    }

    private void flushInspectBuffer(Connection conn, byte[] data, int len) {
        writeUpstream(conn, data, 0, len);
        conn.inspect = ByteBuffer.allocate(0);   // שחרור הזיכרון
    }

    private static boolean isInspectablePort(int port) {
        return port == 443 || port == 80 || port == 8080 || port == 8443;
    }

    private void writeUpstream(Connection conn, byte[] data, int off, int len) {
        if (len <= 0) return;
        byte[] copy = new byte[len];
        System.arraycopy(data, off, copy, 0, len);

        if (!conn.upstreamConnected) {
            if (!enqueuePending(conn, copy, false)) closeConnection(conn, true);
            return;
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(copy);
            while (buf.hasRemaining()) {
                int written = conn.channel.write(buf);
                if (written == 0) {
                    // ה-socket מלא — שומרים את השארית להמשך
                    byte[] rest = new byte[buf.remaining()];
                    buf.get(rest);
                    if (!enqueuePending(conn, rest, false)) {
                        closeConnection(conn, true);
                        return;
                    }
                    interestOps(conn, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "writeUpstream: " + e.getMessage());
            closeConnection(conn, true);
        }
    }

    // ================= מהאינטרנט פנימה =================

    private void selectorLoop() {
        ByteBuffer buffer = ByteBuffer.allocate(RECV_BUFFER);

        while (running) {
            try {
                registerPending();
                selector.select(1000);

                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) continue;

                    Connection conn = (Connection) key.attachment();
                    if (conn == null) continue;

                    try {
                        if (key.isConnectable()) onConnectable(conn);
                        else if (key.isReadable()) onReadable(conn, buffer);
                        if (key.isValid() && key.isWritable()) onWritable(conn);
                    } catch (Exception e) {
                        Log.w(TAG, "key handling: " + e.getMessage());
                        closeConnection(conn, true);
                    }
                }
                cleanupIdle();
            } catch (Exception e) {
                if (running) Log.e(TAG, "selector loop: " + e.getMessage());
            }
        }
    }

    private void registerPending() {
        Connection c;
        while ((c = pendingRegistration.poll()) != null) {
            try {
                c.selectionKey = c.channel.register(selector, SelectionKey.OP_CONNECT, c);
            } catch (Exception e) {
                Log.w(TAG, "register failed: " + e.getMessage());
                closeConnection(c, true);
            }
        }
    }

    /** החיבור ליעד הושלם — רק עכשיו מאשרים את ה-SYN למכשיר. */
    private void onConnectable(Connection conn) {
        try {
            if (!conn.channel.finishConnect()) return;
        } catch (Exception e) {
            Log.d(TAG, "upstream refused " + Ipv4.ipToString(conn.remoteIp) + ": " + e.getMessage());
            sendRst(conn.deviceIp, conn.devicePort, conn.remoteIp, conn.remotePort, conn.mySeq);
            closeConnection(conn, true);
            return;
        }

        conn.upstreamConnected = true;

        // SYN+ACK — מכריזים MSS כדי שהמכשיר לא יפול ל-536 בייט לחבילה
        byte[] synAck = Ipv4.buildTcpWithMss(
            conn.remoteIp, conn.remotePort, conn.deviceIp, conn.devicePort,
            conn.mySeq, conn.theirSeq, Ipv4.SYN | Ipv4.ACK, DEVICE_WINDOW, MSS);
        bridge.writeToTun(synAck, synAck.length);

        conn.mySeq++;                                 // SYN תופס מספר רצף
        conn.myUnacked = conn.mySeq;
        conn.state = State.ESTABLISHED;

        // נתונים שהצטברו בזמן ההמתנה
        flushPendingUpstream(conn);
        interestOps(conn, SelectionKey.OP_READ);
    }

    private void onReadable(Connection conn, ByteBuffer buffer) throws IOException {
        if (conn.windowFull()) {                      // חלון המכשיר מלא — עוצרים
            interestOps(conn, 0);
            return;
        }

        buffer.clear();
        int read = conn.channel.read(buffer);

        if (read < 0) {                               // היעד סגר את החיבור
            byte[] fin = Ipv4.buildTcp(
                conn.remoteIp, conn.remotePort, conn.deviceIp, conn.devicePort,
                conn.mySeq, conn.theirSeq, Ipv4.FIN | Ipv4.ACK, DEVICE_WINDOW,
                null, 0, 0);
            bridge.writeToTun(fin, fin.length);
            conn.mySeq++;
            conn.state = State.CLOSING;
            interestOps(conn, 0);
            return;
        }
        if (read == 0) return;

        buffer.flip();
        byte[] data = new byte[read];
        buffer.get(data);
        conn.lastActive = System.currentTimeMillis();

        // פיצול ל-segments בגודל MSS
        int sent = 0;
        while (sent < read) {
            int chunk = Math.min(MSS, read - sent);
            byte[] seg = Ipv4.buildTcp(
                conn.remoteIp, conn.remotePort, conn.deviceIp, conn.devicePort,
                conn.mySeq, conn.theirSeq, Ipv4.PSH | Ipv4.ACK, DEVICE_WINDOW,
                data, sent, chunk);
            bridge.writeToTun(seg, seg.length);
            conn.mySeq += chunk;
            sent += chunk;
        }
    }

    private void onWritable(Connection conn) {
        flushPendingUpstream(conn);
        synchronized (conn.pendingUpstream) {
            if (conn.pendingUpstream.isEmpty()) {
                interestOps(conn, SelectionKey.OP_READ);
            }
        }
    }

    private void flushPendingUpstream(Connection conn) {
        synchronized (conn.pendingUpstream) {
            while (!conn.pendingUpstream.isEmpty()) {
                byte[] head = conn.pendingUpstream.peek();
                try {
                    ByteBuffer buf = ByteBuffer.wrap(head);
                    conn.channel.write(buf);
                    conn.pendingUpstream.poll();
                    conn.pendingUpstreamBytes -= head.length;
                    if (buf.hasRemaining()) {
                        // ה-socket מלא — מחזירים את השארית לראש התור
                        byte[] rest = new byte[buf.remaining()];
                        buf.get(rest);
                        conn.pendingUpstream.addFirst(rest);
                        conn.pendingUpstreamBytes += rest.length;
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "flushPending: " + e.getMessage());
                    closeConnection(conn, true);
                    return;
                }
            }
        }
    }

    /** Adds backpressure to slow/unreachable upstream sockets. */
    private boolean enqueuePending(Connection conn, byte[] data, boolean front) {
        synchronized (conn.pendingUpstream) {
            if (data == null || data.length == 0) return true;
            if (conn.pendingUpstreamBytes + data.length > MAX_PENDING_UPSTREAM_BYTES) {
                Log.w(TAG, "pending upstream overflow for " + conn.key);
                return false;
            }
            if (front) conn.pendingUpstream.addFirst(data);
            else conn.pendingUpstream.addLast(data);
            conn.pendingUpstreamBytes += data.length;
            return true;
        }
    }

    private void resumeReading(Connection conn) {
        if (conn.state == State.ESTABLISHED && conn.upstreamConnected) {
            interestOps(conn, SelectionKey.OP_READ);
        }
    }

    private void interestOps(Connection conn, int ops) {
        try {
            if (conn.selectionKey != null && conn.selectionKey.isValid()) {
                conn.selectionKey.interestOps(ops);
                selector.wakeup();
            }
        } catch (Exception ignored) {}
    }

    // ================= עזר =================

    private void sendAck(Connection conn) {
        byte[] ack = Ipv4.buildTcp(
            conn.remoteIp, conn.remotePort, conn.deviceIp, conn.devicePort,
            conn.mySeq, conn.theirSeq, Ipv4.ACK, DEVICE_WINDOW, null, 0, 0);
        bridge.writeToTun(ack, ack.length);
    }

    private void sendRst(int deviceIp, int devicePort, int remoteIp, int remotePort, long seq) {
        byte[] rst = Ipv4.buildTcp(
            remoteIp, remotePort, deviceIp, devicePort,
            seq, 0, Ipv4.RST | Ipv4.ACK, 0, null, 0, 0);
        bridge.writeToTun(rst, rst.length);
    }

    private void closeConnection(Connection conn, boolean removeFromMap) {
        if (conn == null) return;
        conn.state = State.CLOSED;
        synchronized (conn.pendingUpstream) {
            conn.pendingUpstream.clear();
            conn.pendingUpstreamBytes = 0;
        }
        try {
            if (conn.selectionKey != null) conn.selectionKey.cancel();
        } catch (Exception ignored) {}
        try {
            if (conn.channel != null) conn.channel.close();
        } catch (Exception ignored) {}
        if (removeFromMap) connections.remove(conn.key);
    }

    private void cleanupIdle() {
        long now = System.currentTimeMillis();
        if (now - lastCleanup < CLEANUP_EVERY_MS) return;
        lastCleanup = now;

        for (Map.Entry<String, Connection> e : connections.entrySet()) {
            Connection c = e.getValue();
            if (c.state == State.CLOSED || now - c.lastActive > IDLE_TIMEOUT_MS) {
                closeConnection(c, false);
                connections.remove(e.getKey());
            }
        }
    }

    public int activeConnections() {
        return connections.size();
    }
}
