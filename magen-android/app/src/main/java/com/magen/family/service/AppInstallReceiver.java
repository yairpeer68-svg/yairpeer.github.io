package com.magen.family.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.magen.family.MagenApp;

/**
 * AppInstallReceiver — מזהה התקנת אפליקציות VPN/proxy ושולח התראה דחופה +
 * פותח אוטומטית מסך הסרת התקנה. לא יכול למנוע התקנה במלואה — זה דורש Device Owner.
 */
public class AppInstallReceiver extends BroadcastReceiver {

    private static final String TAG = "AppInstall";

    /**
     * שמות חבילה מדויקים של אפליקציות VPN/פרוקסי נפוצות.
     *
     * למה לא תת-מחרוזות: הרשימה הקודמת חיפשה בין השאר את "tor", ו-"tor"
     * מופיע בתוך המון שמות תמימים —
     *   com.google.android.apps.docs.editors.docs  ("edi-tor-s")
     *   com.android.storagemanager                 ("s-tor-age")
     *   com.motorola.*                             ("mo-tor-ola")
     *   כל חבילה עם creator / monitor / factory
     * התוצאה הייתה SMS דחוף להורה ופתיחת מסך הסרה — על התקנת Google Docs.
     */
    private static final java.util.Set<String> VPN_PACKAGES = new java.util.HashSet<>(
        java.util.Arrays.asList(
            "org.torproject.android", "org.torproject.torbrowser",
            "com.expressvpn.vpn", "com.nordvpn.android",
            "com.tunnelbear.android", "com.privateinternetaccess.android",
            "com.protonvpn.android", "de.blinkt.openvpn",
            "com.vyprvpn.android", "com.ipvanish.android",
            "com.windscribe.vpn", "com.surfshark.vpnclient.android",
            "com.cyberghostvpn.android", "com.purevpn.purevpnandroid",
            "com.hotspotshield.android.vpn", "com.anchorfree.vpn",
            "com.ultrasurf.android", "org.zwanoo.android.speedify",
            "com.psiphon3", "com.psiphon3.subscription",
            "free.vpn.proxy.secure", "com.fast.free.unblock.thunder.vpn",
            "com.signallab.thunder", "com.pandavpn.androidproxy",
            "com.vpn.free.hotspot.secure.vpnify", "com.atlasvpn.free",
            "com.wireguard.android", "com.v2ray.ang",
            "com.github.shadowsocks", "org.briarproject.briar.android"
        ));

    /**
     * תבניות שם שעדיין שוות בדיקה, אבל רק כרכיב שלם בשם החבילה —
     * כלומר "com.foo.vpn.bar" ייתפס ואילו "com.motorola.x" לא.
     */
    private static final String[] VPN_SEGMENTS = {
        "vpn", "proxy", "psiphon", "shadowsocks", "v2ray", "wireguard", "openvpn"
    };

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String action = intent.getAction();
        String pkg = intent.getData().getSchemeSpecificPart();
        if (pkg == null) return;

        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            if (isVpnApp(pkg)) {
                Log.w(TAG, "🚨 VPN-like app installed: " + pkg);
                try { MagenApp.getInstance().incrementSettingsAttempts(); }
                catch (Exception ignored) {}
                NotificationHelper.notifyPartnerUrgent(ctx,
                    "🚨 הותקנה אפליקציית VPN: " + pkg);
                try {
                    Intent uninstall = new Intent(Intent.ACTION_DELETE,
                        Uri.parse("package:" + pkg));
                    uninstall.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(uninstall);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * זיהוי אפליקציית VPN: התאמה מדויקת לרשימה, או רכיב שלם בשם החבילה.
     * פיצול לפי '.' הוא מה שמונע את ההתאמות השווא של "editors"/"storage".
     */
    static boolean isVpnApp(String pkg) {
        String lower = pkg.toLowerCase();
        if (VPN_PACKAGES.contains(lower)) return true;

        for (String segment : lower.split("\\.")) {
            for (String s : VPN_SEGMENTS) {
                if (segment.equals(s)) return true;
            }
        }
        return false;
    }
}
