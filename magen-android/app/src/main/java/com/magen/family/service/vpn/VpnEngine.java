package com.magen.family.service.vpn;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * VpnEngine — מנתב כל חבילה שיוצאת מהמכשיר לשכבה המתאימה.
 *
 * מדיניות החבילות:
 *
 *   IPv6            נזרק. המסנן עובד על IPv4, ולולא החסימה כל התעבורה הייתה
 *                   יכולה לעקוף אותו דרך IPv6.
 *   IPv4 מפוצל      נזרק (אין תמיכה בהרכבה מחדש; נדיר מאוד בפועל).
 *   UDP             UdpRelay — כולל סינון DNS, חסימת QUIC ו-DoT.
 *   TCP             TcpRelay — כולל סינון SNI.
 *   ICMP ואחרים     נזרקים.
 *
 * מנגנון בטיחות:
 *   מצב full tunnel אומר שכל תעבורת המכשיר עוברת דרך הקוד הזה. אם משהו בו
 *   נשבר, המשתמש נשאר בלי אינטרנט. לכן נספרים כשלים, ומעל סף מסוים המנוע
 *   מכבה את עצמו ומסמן ל-VpnService לחזור למצב DNS-only — עדיף סינון חלקי
 *   על פני מכשיר מנותק.
 */
public class VpnEngine {

    private static final String TAG = "VpnEngine";

    /** מעל כמות כשלים כזו בחלון זמן — נסיגה למצב בטוח. */
    private static final int  FAILURE_THRESHOLD  = 50;
    private static final long FAILURE_WINDOW_MS  = 60_000L;

    private final TunBridge bridge;
    private UdpRelay udpRelay;
    private TcpRelay tcpRelay;

    private final AtomicInteger failures = new AtomicInteger();
    private volatile long failureWindowStart = 0;
    private volatile boolean degraded = false;
    private volatile boolean running = false;

    public VpnEngine(TunBridge bridge) {
        this.bridge = bridge;
    }

    public void start() throws IOException {
        udpRelay = new UdpRelay(bridge);
        tcpRelay = new TcpRelay(bridge);
        udpRelay.start();
        tcpRelay.start();
        running = true;
        degraded = false;
        Log.d(TAG, "engine started (fullTunnel=" + VpnPolicy.fullTunnel() + ")");
    }

    public void stop() {
        running = false;
        if (udpRelay != null) udpRelay.stop();
        if (tcpRelay != null) tcpRelay.stop();
        Log.d(TAG, "engine stopped. " + VpnStats.summary());
    }

    /** האם המנוע ביקש נסיגה למצב DNS-only? */
    public boolean isDegraded() { return degraded; }

    /**
     * מעבד חבילה אחת מה-TUN. נקרא מה-thread של לולאת הקריאה בלבד.
     */
    public void processPacket(byte[] packet, int length) {
        if (!running || length < 20) return;

        try {
            int version = Ipv4.version(packet);

            if (version == 6) return;                 // חור דליפת IPv6 סגור
            if (version != 4) return;

            int ihl = Ipv4.ihl(packet);
            if (ihl < 20 || ihl > length) return;
            if (Ipv4.isFragment(packet)) return;

            switch (Ipv4.protocol(packet)) {
                case Ipv4.PROTO_UDP:
                    if (length < ihl + 8) return;
                    udpRelay.handleOutbound(packet, length);
                    break;

                case Ipv4.PROTO_TCP:
                    if (length < ihl + 20) return;
                    tcpRelay.handleOutbound(packet, length);
                    break;

                default:
                    // ICMP וכל השאר — לא נדרשים לסינון ולא מועברים
                    break;
            }
        } catch (Exception e) {
            reportFailure(e);
        }
    }

    /**
     * סופר כשלים. אם המנוע לא יציב — מוותרים על full tunnel כדי לא להשאיר
     * את המכשיר בלי רשת.
     */
    private void reportFailure(Exception e) {
        long now = System.currentTimeMillis();
        if (now - failureWindowStart > FAILURE_WINDOW_MS) {
            failureWindowStart = now;
            failures.set(0);
        }

        int count = failures.incrementAndGet();
        if (count <= 3) Log.w(TAG, "packet error: " + e.getMessage());

        if (count >= FAILURE_THRESHOLD && !degraded) {
            degraded = true;
            VpnPolicy.disableFullTunnelForSession();
            Log.e(TAG, "too many failures — falling back to DNS-only mode");
        }
    }

    public String statusLine() {
        int udp = udpRelay == null ? 0 : udpRelay.activeSessions();
        int tcp = tcpRelay == null ? 0 : tcpRelay.activeConnections();
        return "udp=" + udp + " tcp=" + tcp + " " + VpnStats.summary();
    }
}
