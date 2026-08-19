package com.magen.family.service.vpn;

import android.os.SystemClock;
import android.util.Log;

import com.magen.family.filter.DomainVerdict;
import com.magen.family.mitm.HttpsInspectionProxy;
import com.magen.family.mitm.MitmPolicy;

import java.nio.charset.StandardCharsets;

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
import java.security.SecureRandom;
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
    private static final int INSPECT_LIMIT     = 16 * 1024; // fragmented/large ClientHello
    private static final int DEVICE_WINDOW     = 65535;
    private static final int MAX_PENDING_UPSTREAM_BYTES = 512 * 1024;
    private static final long IDLE_TIMEOUT_MS  = 300_000L;
    private static final long CLEANUP_EVERY_MS = 30_000L;

    private enum State { CONNECTING, ESTABLISHED, CLOSING, CLOSED }

    private final TunBridge bridge;
    private final Selector selector;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Connection> pendingRegistration = new ConcurrentLinkedQueue<>();
    /** Redirect work is executed on the selector thread so a selected old key cannot race a replacement channel. */
    private final ConcurrentLinkedQueue<Connection> pendingInspectionRedirect = new ConcurrentLinkedQueue<>();

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
        int  deviceWindowScale = 0;

        volatile State state = State.CONNECTING;
        /** The current kernel SocketChannel has completed its connect(). */
        volatile boolean socketConnected = false;
        /** Device bytes may be forwarded only after both socket connect and the final TCP ACK. */
        volatile boolean upstreamConnected = false;
        /** True once our SYN+ACK has been emitted; it is still unacknowledged until the final ACK. */
        volatile boolean synAckSent = false;
        /** True only after the original three-way handshake with the device completed. */
        volatile boolean deviceHandshakeComplete = false;
        /** Device has sent an in-order FIN. */
        volatile boolean deviceFinReceived = false;
        /** shutdownOutput() was applied only after every queued device byte reached upstream. */
        volatile boolean upstreamOutputShutdown = false;
        /** Kernel upstream returned EOF; OP_READ must never be re-enabled afterwards. */
        volatile boolean upstreamInputEof = false;
        /** We sent FIN to the device after upstream EOF. */
        volatile boolean finSentToDevice = false;
        /** Device acknowledged our FIN sequence number. */
        volatile boolean finAckedByDevice = false;
        /** Current upstream is the loopback transparent HTTPS-inspection listener. */
        volatile boolean inspectionRedirect = false;
        /** Original SNI host used only in-memory while this TCP flow is alive. */
        volatile String inspectionHost = null;
        /** A redirect request waiting for the selector thread. */
        volatile boolean redirectRequested = false;
        /** True while recovering a failed loopback redirect back to the real destination. */
        volatile boolean reconnectingOriginal = false;

        /** בייטים ראשונים מהמכשיר, לצורך חילוץ SNI/Host. */
        ByteBuffer inspect = ByteBuffer.allocate(INSPECT_LIMIT);
        boolean verdictResolved = false;

        /** נתונים שממתינים עד שהחיבור ליעד יושלם. */
        final ArrayDeque<byte[]> pendingUpstream = new ArrayDeque<>();
        int pendingUpstreamBytes = 0;

        volatile long lastActive = SystemClock.elapsedRealtime();

        /** כמה בייטים בדרך למכשיר שעדיין לא אושרו. */
        long inFlight() { return mySeq - myUnacked; }

        int availableWindow() { return TcpWindow.available(mySeq, myUnacked, deviceWindow); }
        boolean windowFull() { return availableWindow() <= 0; }
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
        if (packet == null || length < 40 || length > packet.length) return;
        int ihl        = Ipv4.ihl(packet);
        if (ihl < 20 || ihl > length - 20) return;
        int deviceIp   = Ipv4.srcIp(packet);
        int remoteIp   = Ipv4.dstIp(packet);
        int devicePort = Ipv4.srcPort(packet, ihl);
        int remotePort = Ipv4.dstPort(packet, ihl);

        long seq32 = Ipv4.tcpSeq(packet, ihl);
        long ack32 = Ipv4.tcpAck(packet, ihl);
        int  flags = Ipv4.tcpFlags(packet, ihl);
        int  window = Ipv4.tcpWindow(packet, ihl);

        int dataOffset = Ipv4.tcpDataOffset(packet, ihl);
        if (dataOffset < 20 || dataOffset > 60 || dataOffset > length - ihl) return;
        int payloadOff = ihl + dataOffset;
        int payloadLen = length - payloadOff;

        // DNS-over-TCP ו-DoT — נזרקים לכל יעד
        if (remotePort == 53) { VpnStats.countBlockedDot(); sendRst(deviceIp, devicePort, remoteIp, remotePort, (seq32 + 1) & 0xFFFFFFFFL); return; }
        if (remotePort == 853) { VpnStats.countBlockedDot(); sendRst(deviceIp, devicePort, remoteIp, remotePort, (seq32 + 1) & 0xFFFFFFFFL); return; }

        String key = devicePort + ">" + remoteIp + ":" + remotePort;
        Connection conn = connections.get(key);

        // --- SYN: חיבור חדש ---
        if ((flags & Ipv4.SYN) != 0 && (flags & Ipv4.ACK) == 0) {
            if (conn != null) {
                long expectedSyn = conn.theirSeq - 1L;
                long incomingSyn = TcpSeq.unwrap(seq32, expectedSyn);
                if (incomingSyn == expectedSyn && conn.synAckSent && !conn.deviceHandshakeComplete) {
                    // The device did not receive our first SYN+ACK. Re-send the exact same
                    // sequence number; retransmission must not consume another sequence number.
                    sendSynAck(conn, false);
                    conn.lastActive = SystemClock.elapsedRealtime();
                    return;
                }
                // Same 4-tuple with a different ISN is a new connection (port reuse) rather than
                // a retransmission. Retire stale state before accepting it.
                closeConnection(conn, true);
            }
            openConnection(key, deviceIp, devicePort, remoteIp, remotePort, seq32, window,
                TcpWindow.parseWindowScale(packet, ihl, dataOffset));
            return;
        }

        if (conn == null) {
            // חבילה לחיבור שלא מוכר — מודיעים לשולח שאין חיבור
            sendRst(deviceIp, devicePort, remoteIp, remotePort, seq32);
            return;
        }

        conn.lastActive = SystemClock.elapsedRealtime();
        conn.deviceWindow = TcpWindow.scale(window, conn.deviceWindowScale);

        if ((flags & Ipv4.RST) != 0) {
            closeConnection(conn, true);
            return;
        }

        if ((flags & Ipv4.ACK) != 0) {
            // The SYN+ACK consumes one sequence number. Do not declare the device-side TCP
            // handshake complete merely because the upstream socket connected; wait for the
            // device to acknowledge exactly our next sequence number.
            long ack = TcpSeq.unwrap(ack32, conn.myUnacked);
            if (!conn.deviceHandshakeComplete && conn.synAckSent && ack == conn.mySeq) {
                conn.myUnacked = conn.mySeq;
                conn.deviceHandshakeComplete = true;
                conn.state = State.ESTABLISHED;
                if (conn.socketConnected) {
                    conn.upstreamConnected = true;
                    synchronized (conn.pendingUpstream) {
                        interestOps(conn, conn.pendingUpstream.isEmpty()
                            ? SelectionKey.OP_READ : (SelectionKey.OP_READ | SelectionKey.OP_WRITE));
                    }
                }
            } else if (conn.deviceHandshakeComplete && ack > conn.myUnacked && ack <= conn.mySeq) {
                conn.myUnacked = ack;
            }
            if (conn.finSentToDevice && ack == conn.mySeq) conn.finAckedByDevice = true;
            if (maybeCloseGracefully(conn)) return;
            // התפנה מקום בחלון — אפשר לחדש קריאה מהאינטרנט
            if (conn.deviceHandshakeComplete && !conn.windowFull()) resumeReading(conn);
        }

        if (payloadLen > 0) {
            if (conn.deviceFinReceived) {                // no data is valid after an accepted FIN
                sendAck(conn);
                return;
            }
            long seq = TcpSeq.unwrap(seq32, conn.theirSeq);
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
            // FIN consumes sequence space only when it is exactly the next expected byte.
            // Duplicate/out-of-order FINs receive the current ACK but never advance twice.
            long finSeq32 = (seq32 + payloadLen) & 0xFFFFFFFFL;
            long finSeq = TcpSeq.unwrap(finSeq32, conn.theirSeq);
            if (finSeq == conn.theirSeq && !conn.deviceFinReceived) {
                conn.theirSeq++;
                conn.deviceFinReceived = true;
                conn.state = State.CLOSING;
                // Do not call shutdownOutput() here: payload+FIN may have just queued the final
                // device bytes. Half-close only after the selector has flushed that queue.
                maybeShutdownUpstreamOutput(conn);
            }
            sendAck(conn);
            if (maybeCloseGracefully(conn)) return;
        }
    }

    private void openConnection(String key, int deviceIp, int devicePort,
                                int remoteIp, int remotePort, long seq, int window, int windowScale) {
        Connection conn = new Connection();
        conn.key        = key;
        conn.deviceIp   = deviceIp;
        conn.devicePort = devicePort;
        conn.remoteIp   = remoteIp;
        conn.remotePort = remotePort;
        conn.theirSeq   = seq + 1;                    // SYN תופס מספר רצף
        conn.mySeq      = random.nextInt() & 0xFFFFFFFFL;
        conn.myUnacked  = conn.mySeq;
        conn.deviceWindowScale = Math.max(0, Math.min(windowScale, 14));
        conn.deviceWindow = TcpWindow.scale(window, conn.deviceWindowScale);

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

                // Transparent HTTPS inspection is stronger than Android's recommended HTTP proxy:
                // the original ClientHello has not been sent upstream yet, so the selector thread
                // can safely replace the remote socket with a protected loopback socket. Sensitive
                // or compatibility-fallback hosts remain end-to-end encrypted.
                if (conn.remotePort == 443
                        && MitmPolicy.shouldIntercept(bridge.context(), host)
                        && HttpsInspectionProxy.get(bridge.context()).isRunning()) {
                    if (!enqueuePending(conn, java.util.Arrays.copyOf(snapshot, snapshotLen), false)) {
                        closeConnection(conn, true);
                        return false;
                    }
                    if (copy < len) {
                        byte[] tail = new byte[len - copy];
                        System.arraycopy(packet, off + copy, tail, 0, tail.length);
                        if (!enqueuePending(conn, tail, false)) {
                            closeConnection(conn, true);
                            return false;
                        }
                    }
                    conn.inspect = ByteBuffer.allocate(0);
                    conn.inspectionHost = host;
                    // Freeze device->upstream writes immediately. Subsequent packets are queued
                    // until the selector thread has atomically replaced the real socket.
                    conn.upstreamConnected = false;
                    conn.redirectRequested = true;
                    pendingInspectionRedirect.add(conn);
                    selector.wakeup();
                    return true;
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

        // Single-writer rule: the packet thread never writes a SocketChannel directly. It only
        // appends to the bounded queue; the selector thread performs all upstream writes. This
        // preserves byte ordering during transparent redirect/reconnect and removes a subtle race
        // between onWritable()/ClientHello redirect and a fast stream of device packets.
        if (!enqueuePending(conn, copy, false)) {
            closeConnection(conn, true);
            return;
        }
        if (conn.upstreamConnected) refreshInterestOps(conn);
        selector.wakeup();
    }

    // ================= מהאינטרנט פנימה =================

    private void selectorLoop() {
        ByteBuffer buffer = ByteBuffer.allocate(RECV_BUFFER);

        while (running) {
            try {
                processInspectionRedirects();
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

    private void processInspectionRedirects() {
        Connection conn;
        while ((conn = pendingInspectionRedirect.poll()) != null) {
            if (conn.state == State.CLOSED || !conn.redirectRequested) continue;
            conn.redirectRequested = false;
            String host = conn.inspectionHost;
            if (host == null || host.isEmpty()) continue;

            // This method runs only on the selector thread. Cancelling the old key and replacing
            // the channel here avoids a race where an event from the real upstream could close the
            // newly-created loopback channel.
            try {
                if (conn.selectionKey != null) conn.selectionKey.cancel();
            } catch (Exception ignored) {}
            try {
                if (conn.channel != null) conn.channel.close();
            } catch (Exception ignored) {}
            conn.selectionKey = null;
            conn.upstreamConnected = false;
            conn.inspectionRedirect = true;
            conn.reconnectingOriginal = false;

            try {
                SocketChannel ch = SocketChannel.open();
                ch.configureBlocking(false);
                if (!bridge.protect(ch.socket())) throw new IOException("protect loopback failed");
                conn.channel = ch;
                ch.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),
                    HttpsInspectionProxy.TRANSPARENT_PORT));
                pendingRegistration.add(conn);
            } catch (Exception e) {
                Log.d(TAG, "transparent inspection unavailable; restoring encrypted tunnel");
                MitmPolicy.markFallback(bridge.context(), host, "LOCAL_PROXY_UNAVAILABLE");
                reconnectOriginal(conn);
            }
        }
    }

    private void registerPending() {
        Connection c;
        while ((c = pendingRegistration.poll()) != null) {
            try {
                int ops = c.channel.isConnected() ? SelectionKey.OP_READ : SelectionKey.OP_CONNECT;
                c.selectionKey = c.channel.register(selector, ops, c);
                // A non-blocking loopback connect can complete immediately and therefore never
                // produce OP_CONNECT. Finish the transition synchronously on the selector thread.
                if (c.channel.isConnected()) onConnectable(c);
            } catch (Exception e) {
                Log.w(TAG, "register failed: " + e.getMessage());
                if (c.inspectionRedirect && c.deviceHandshakeComplete) reconnectOriginal(c);
                else closeConnection(c, true);
            }
        }
    }

    /**
     * Opens the original destination again without repeating the device-side SYN handshake.
     * Pending bytes contain the untouched ClientHello, so fallback remains end-to-end encrypted.
     */
    private void reconnectOriginal(Connection conn) {
        try {
            if (conn.selectionKey != null) conn.selectionKey.cancel();
        } catch (Exception ignored) {}
        try {
            if (conn.channel != null) conn.channel.close();
        } catch (Exception ignored) {}
        conn.selectionKey = null;
        conn.socketConnected = false;
        conn.upstreamConnected = false;
        conn.inspectionRedirect = false;
        conn.reconnectingOriginal = true;

        try {
            SocketChannel ch = SocketChannel.open();
            ch.configureBlocking(false);
            if (!bridge.protect(ch.socket())) throw new IOException("protect fallback failed");
            conn.channel = ch;
            ch.connect(new InetSocketAddress(
                InetAddress.getByAddress(Ipv4.ipToBytes(conn.remoteIp)), conn.remotePort));
            pendingRegistration.add(conn);
            selector.wakeup();
        } catch (Exception e) {
            sendRst(conn.deviceIp, conn.devicePort, conn.remoteIp, conn.remotePort, conn.mySeq);
            closeConnection(conn, true);
        }
    }

    /** Send the original SYN+ACK or retransmit it without advancing our sequence twice. */
    private void sendSynAck(Connection conn, boolean firstTransmission) {
        long seq = firstTransmission ? conn.mySeq : conn.mySeq - 1L;
        byte[] synAck = Ipv4.buildTcpWithMss(
            conn.remoteIp, conn.remotePort, conn.deviceIp, conn.devicePort,
            seq, conn.theirSeq, Ipv4.SYN | Ipv4.ACK, DEVICE_WINDOW, MSS);
        bridge.writeToTun(synAck, synAck.length);
        if (firstTransmission) {
            conn.mySeq++;
            conn.synAckSent = true;
        }
    }

    /** החיבור ליעד הושלם. SYN+ACK נשלח רק פעם אחת, עבור החיבור המקורי. */
    private void onConnectable(Connection conn) {
        try {
            if (!conn.channel.isConnected() && !conn.channel.finishConnect()) return;
        } catch (Exception e) {
            if (conn.inspectionRedirect && conn.deviceHandshakeComplete) {
                String host = conn.inspectionHost;
                if (host != null) MitmPolicy.markFallback(bridge.context(), host, "LOCAL_PROXY_CONNECT");
                reconnectOriginal(conn);
                return;
            }
            Log.d(TAG, "upstream refused " + Ipv4.ipToString(conn.remoteIp) + ": " + e.getMessage());
            sendRst(conn.deviceIp, conn.devicePort, conn.remoteIp, conn.remotePort, conn.mySeq);
            closeConnection(conn, true);
            return;
        }

        conn.socketConnected = true;
        // Keep device writes queued until the device has acknowledged our SYN+ACK and until any
        // private inspection prefix is placed at the front.
        conn.upstreamConnected = false;

        if (!conn.synAckSent) {
            // SYN+ACK — מכריזים MSS כדי שהמכשיר לא יפול ל-536 בייט לחבילה. The SYN remains
            // unacknowledged here; only handleOutbound() may complete the three-way handshake.
            sendSynAck(conn, true);
            conn.state = State.CONNECTING;
            interestOps(conn, 0);                         // no upstream read before final ACK
            return;
        } else if (conn.inspectionRedirect) {
            // Private loopback metadata always precedes the untouched TLS ClientHello. It never
            // leaves the device and contains only the SNI hostname already visible to the relay.
            byte[] prefix = HttpsInspectionProxy.get(bridge.context())
                .transparentPreamble(conn.inspectionHost).getBytes(StandardCharsets.US_ASCII);
            if (!enqueuePending(conn, prefix, true)) {
                closeConnection(conn, true);
                return;
            }
        }

        conn.reconnectingOriginal = false;
        if (!conn.deviceHandshakeComplete) {
            interestOps(conn, 0);
            return;
        }
        conn.upstreamConnected = true;
        flushPendingUpstream(conn);
        maybeShutdownUpstreamOutput(conn);
        refreshInterestOps(conn);
    }

    private void onReadable(Connection conn, ByteBuffer buffer) throws IOException {
        int available = conn.availableWindow();
        if (available <= 0) {                         // zero-window באמת עוצר קריאה
            refreshInterestOps(conn);                  // keep OP_WRITE if device data is queued
            return;
        }

        buffer.clear();
        buffer.limit(Math.min(buffer.capacity(), available));
        int read = conn.channel.read(buffer);

        if (read < 0) {                               // היעד סגר את צד הקריאה
            conn.upstreamInputEof = true;
            if (!conn.finSentToDevice) {
                byte[] fin = Ipv4.buildTcp(
                    conn.remoteIp, conn.remotePort, conn.deviceIp, conn.devicePort,
                    conn.mySeq, conn.theirSeq, Ipv4.FIN | Ipv4.ACK, DEVICE_WINDOW,
                    null, 0, 0);
                bridge.writeToTun(fin, fin.length);
                conn.mySeq++;
                conn.finSentToDevice = true;
            }
            conn.state = State.CLOSING;
            refreshInterestOps(conn);                  // never re-enable OP_READ after EOF
            maybeCloseGracefully(conn);
            return;
        }
        if (read == 0) return;

        buffer.flip();
        byte[] data = new byte[read];
        buffer.get(data);
        conn.lastActive = SystemClock.elapsedRealtime();

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
        refreshInterestOps(conn);
    }

    private void onWritable(Connection conn) {
        flushPendingUpstream(conn);
        maybeShutdownUpstreamOutput(conn);
        refreshInterestOps(conn);
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
        if (conn.upstreamConnected) refreshInterestOps(conn);
    }

    /** Derive selector interests from both TCP directions; never lose OP_WRITE when pausing reads. */
    private void refreshInterestOps(Connection conn) {
        if (conn == null || !conn.upstreamConnected || conn.state == State.CLOSED) {
            interestOps(conn, 0);
            return;
        }
        int ops = 0;
        if (!conn.upstreamInputEof && !conn.windowFull()) ops |= SelectionKey.OP_READ;
        synchronized (conn.pendingUpstream) {
            if (!conn.pendingUpstream.isEmpty() && !conn.upstreamOutputShutdown) ops |= SelectionKey.OP_WRITE;
        }
        interestOps(conn, ops);
    }

    /** Half-close upstream only after every byte preceding the device FIN has been flushed. */
    private void maybeShutdownUpstreamOutput(Connection conn) {
        if (conn == null || !conn.deviceFinReceived || conn.upstreamOutputShutdown
                || !conn.socketConnected || conn.channel == null) return;
        synchronized (conn.pendingUpstream) {
            if (!conn.pendingUpstream.isEmpty()) return;
        }
        try {
            if (conn.channel.isConnected()) conn.channel.shutdownOutput();
            conn.upstreamOutputShutdown = true;
        } catch (Exception e) {
            closeConnection(conn, true);
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

    /** Close immediately once both half-closes and our FIN acknowledgement are complete. */
    private boolean maybeCloseGracefully(Connection conn) {
        if (conn.deviceFinReceived && conn.finSentToDevice && conn.finAckedByDevice) {
            closeConnection(conn, true);
            return true;
        }
        return false;
    }

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
        conn.socketConnected = false;
        conn.upstreamConnected = false;
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
        long now = SystemClock.elapsedRealtime();
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
