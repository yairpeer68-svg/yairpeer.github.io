package com.magen.family.service.vpn;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * VpnPolicy — כל ההחלטות הניתנות להגדרה של מנוע ה-VPN במקום אחד.
 *
 * חשוב במיוחד: FULL_TUNNEL.
 *   במצב full tunnel המנוע מנתב 0.0.0.0/0 — כלומר *כל* תעבורת המכשיר עוברת
 *   דרך קוד Java שאנחנו כתבנו. זה מה שסוגר את חור ה-DNS השרירותי ומאפשר
 *   סינון SNI, אבל זה גם אומר שבאג במנוע = אין אינטרנט במכשיר.
 *
 *   לכן ברירת המחדל היא OFF, ויש נפילה אוטומטית חזרה למצב DNS-only אם
 *   המנוע נתקל בכשלים חוזרים (ראה VpnEngine.reportFailure).
 */
public final class VpnPolicy {

    private static final String PREFS = "magen_vpn_policy";

    private static final String K_FULL_TUNNEL = "full_tunnel";
    private static final String K_BLOCK_QUIC  = "block_quic";
    private static final String K_SNI_FILTER  = "sni_filter";
    private static final String K_UPSTREAM    = "upstream_dns";
    private static final String K_BLOCK_HOTSPOT = "block_hotspot";

    /** AdGuard Family — חוסם תוכן מבוגרים כבר ברמת ה-DNS. */
    public static final String DEFAULT_UPSTREAM_DNS = "94.140.14.15";
    public static final String FALLBACK_UPSTREAM_DNS = "94.140.15.16";

    private static volatile SharedPreferences prefs;
    private static volatile boolean fullTunnelRuntime = false;
    private static volatile boolean fullTunnelDisabledForSession = false;

    private VpnPolicy() {}

    public static void init(Context ctx) {
        if (prefs == null) {
            prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
        fullTunnelRuntime = !fullTunnelDisabledForSession && prefs.getBoolean(K_FULL_TUNNEL, false);
    }

    /**
     * מצב full tunnel. ברירת מחדל OFF — ראה הסבר בראש המחלקה.
     * הערך ה"חי" מוחזק גם בזיכרון כדי שנפילה אוטומטית תשפיע מיד.
     */
    public static boolean fullTunnel() {
        return fullTunnelRuntime;
    }

    public static void setFullTunnel(Context ctx, boolean enabled) {
        init(ctx);
        fullTunnelDisabledForSession = false;
        fullTunnelRuntime = enabled;
        prefs.edit().putBoolean(K_FULL_TUNNEL, enabled).apply();
    }

    /** כיבוי זמני בזיכרון בלבד — ההגדרה השמורה נשארת, כדי לנסות שוב בהפעלה הבאה. */
    public static void disableFullTunnelForSession() {
        fullTunnelDisabledForSession = true;
        fullTunnelRuntime = false;
    }

    /** חסימת QUIC (UDP/443) כדי לאלץ נפילה ל-TCP שבו ה-SNI גלוי. */
    public static boolean blockQuic() {
        return prefs == null || prefs.getBoolean(K_BLOCK_QUIC, true);
    }

    public static void setBlockQuic(Context ctx, boolean enabled) {
        init(ctx);
        prefs.edit().putBoolean(K_BLOCK_QUIC, enabled).apply();
    }

    /** סינון לפי SNI/Host בתוך חיבורי TCP. רלוונטי רק ב-full tunnel. */
    public static boolean sniFilter() {
        return prefs == null || prefs.getBoolean(K_SNI_FILTER, true);
    }

    public static void setSniFilter(Context ctx, boolean enabled) {
        init(ctx);
        prefs.edit().putBoolean(K_SNI_FILTER, enabled).apply();
    }

    /** חסימת ECH (מענה NODATA ל-HTTPS/SVCB) כדי לשמור על SNI גלוי. */
    public static boolean blockEch() {
        return prefs == null || prefs.getBoolean("block_ech", true);   // דלוק כברירת מחדל
    }

    /** מדיניות תגובה על שיתוף אינטרנט. ראה HotspotGuard — זיהוי, לא מניעה. */
    public static boolean blockHotspot() {
        return prefs != null && prefs.getBoolean(K_BLOCK_HOTSPOT, false);
    }

    public static void setBlockHotspot(Context ctx, boolean enabled) {
        init(ctx);
        prefs.edit().putBoolean(K_BLOCK_HOTSPOT, enabled).apply();
    }

    public static String upstreamDns() {
        return prefs == null
            ? DEFAULT_UPSTREAM_DNS
            : prefs.getString(K_UPSTREAM, DEFAULT_UPSTREAM_DNS);
    }

    public static void setUpstreamDns(Context ctx, String ip) {
        init(ctx);
        prefs.edit().putString(K_UPSTREAM, ip).apply();
    }
}
