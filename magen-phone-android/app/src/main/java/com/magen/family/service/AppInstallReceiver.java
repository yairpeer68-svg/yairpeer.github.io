package com.magen.family.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.VpnService;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ApplicationInfo;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.util.Log;

import com.magen.family.MagenApp;

/**
 * AppInstallReceiver — מזהה התקנת אפליקציות VPN/proxy, מדווח לשרת +
 * פותח אוטומטית מסך הסרת התקנה. לא יכול למנוע התקנה במלואה — זה דורש Device Owner.
 */
public class AppInstallReceiver extends BroadcastReceiver {

    private static final String TAG = "AppInstall";
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> VPN_CAP_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * שמות חבילה מדויקים של אפליקציות VPN/פרוקסי נפוצות.
     *
     * למה לא תת-מחרוזות: הרשימה הקודמת חיפשה בין השאר את "tor", ו-"tor"
     * מופיע בתוך המון שמות תמימים —
     *   com.google.android.apps.docs.editors.docs  ("edi-tor-s")
     *   com.android.storagemanager                 ("s-tor-age")
     *   com.motorola.*                             ("mo-tor-ola")
     *   כל חבילה עם creator / monitor / factory
     * התוצאה הייתה false-positive ופתיחת מסך הסרה — אפילו על אפליקציות תמימות.
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
        // Magen itself declares a VpnService; never classify our own install/update as a competing VPN.
        if (pkg.equals(ctx.getPackageName())) return;

        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            VPN_CAP_CACHE.remove(pkg);
            if (isVpnCapable(ctx, pkg)) {
                blockVpnPackage(ctx, pkg, true);
            }
        }
    }


    /**
     * זיהוי אמיתי של VPN: קודם שואלים את PackageManager האם החבילה מצהירה
     * על service שמטפל ב-android.net.VpnService. כך גם VPN חדש עם שם חבילה
     * אקראי נתפס, ולא רק מותגים שנמצאים ברשימה שלנו.
     */
    public static boolean isVpnCapable(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (ctx != null && pkg.equals(ctx.getPackageName())) return false;
        Boolean cached = VPN_CAP_CACHE.get(pkg);
        if (cached != null) return cached;

        boolean result = isVpnApp(pkg);
        if (!result) {
            try {
                Intent probe = new Intent(VpnService.SERVICE_INTERFACE);
                probe.setPackage(pkg);
                PackageManager pm = ctx.getPackageManager();
                java.util.List<ResolveInfo> matches = pm.queryIntentServices(probe, PackageManager.GET_META_DATA);
                if (matches != null) {
                    for (ResolveInfo ri : matches) {
                        if (ri != null && ri.serviceInfo != null &&
                                "android.permission.BIND_VPN_SERVICE".equals(ri.serviceInfo.permission)) {
                            result = true;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "VPN capability probe failed for " + pkg + ": " + e.getMessage());
            }
        }
        VPN_CAP_CACHE.put(pkg, result);
        return result;
    }

    /**
     * סריקה תקופתית של VPNים שכבר מותקנים. Device Owner משעה/מסתיר אותם ברמת
     * Android; בהתקנה רגילה הם נכנסים לרשימת החסימה ונחסמים ב-Accessibility.
     */
    public static void enforceInstalledVpnApps(Context ctx) {
        if (ctx == null) return;
        PackageManager pm = ctx.getPackageManager();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                if (ai == null || ai.packageName == null || ai.packageName.equals(ctx.getPackageName())) continue;
                // לא משעים רכיבי מערכת אנדרואיד רק משום שהם משתמשים ב-VPN API פנימי.
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (system && !isVpnApp(ai.packageName)) continue;
                if (isVpnCapable(ctx, ai.packageName)) blockVpnPackage(ctx, ai.packageName, false);
            }
        } catch (Exception e) {
            Log.w(TAG, "installed VPN scan failed: " + e.getMessage());
        }
    }

    private static void blockVpnPackage(Context ctx, String pkg, boolean newlyInstalled) {
        if (ctx == null || pkg == null || pkg.equals(ctx.getPackageName())) return;
        Log.w(TAG, "🚨 VPN-like app blocked: " + pkg);
        try { MagenApp.getInstance().incrementSettingsAttempts(); } catch (Exception ignored) {}
        com.magen.family.MagenConfig.setAppBlocked(ctx, pkg, true);

        boolean managed = false;
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && (dpm.isDeviceOwnerApp(ctx.getPackageName()) || dpm.isProfileOwnerApp(ctx.getPackageName()))) {
                ComponentName admin = com.magen.family.admin.MagenDeviceAdmin.getComponentName(ctx);
                try { dpm.setPackagesSuspended(admin, new String[]{pkg}, true); managed = true; }
                catch (Exception e) { Log.w(TAG, "suspend VPN failed: " + e.getMessage()); }
                try { dpm.setApplicationHidden(admin, pkg, true); managed = true; }
                catch (Exception e) { Log.w(TAG, "hide VPN failed: " + e.getMessage()); }
            }
        } catch (Exception e) { Log.w(TAG, "managed VPN block failed: " + e.getMessage()); }

        com.magen.family.server.ServerEventReporter.report(ctx,
            newlyInstalled ? "VPN_APP_INSTALLED" : "VPN_APP_PRESENT", "HIGH",
            "package=" + pkg + " managed=" + managed);
        if (newlyInstalled) {
            NotificationHelper.notifyUrgent(ctx, "🚨 הותקנה אפליקציית VPN/Proxy ונחסמה: " + pkg);
            if (!managed) {
                try {
                    Intent uninstall = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
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
    public static boolean isVpnApp(String pkg) {
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
