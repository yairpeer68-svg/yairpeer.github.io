package com.magen.family.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.magen.family.filter.DomainVerdict;
import com.magen.family.server.ServerEventReporter;
import com.magen.family.service.vpn.TunBridge;
import com.magen.family.service.vpn.VpnEngine;
import com.magen.family.service.vpn.VpnPolicy;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * MagenVpnService — שכבת הסינון ברמת הרשת.
 *
 * שני מצבי עבודה:
 *
 *   DNS-only (ברירת מחדל)
 *     מנתב לתוך המנהרה רק את ה-DNS שלנו ואת ספקי ה-DNS הידועים, ומסנן
 *     שאילתות. יציב מאוד, אבל מי שמפנה שאילתה ל-resolver שאינו ברשימה
 *     פשוט לא עובר דרכנו.
 *
 *   Full tunnel (VpnPolicy.setFullTunnel)
 *     מנתב 0.0.0.0/0. *כל* חבילה עוברת דרך המנוע: סינון DNS לכל יעד,
 *     סינון SNI בתוך חיבורי TLS, חסימת QUIC ו-DoT. זו ההגנה החזקה ביותר
 *     שאפשר להשיג בלי Device Owner — אבל היא גם אומרת שבאג במנוע משבית
 *     את הרשת, ולכן היא כבויה כברירת מחדל ויש נסיגה אוטומטית.
 *
 * תיקונים מהגרסה הקודמת:
 *   • השירות קורא ל-startForeground(). קודם הוא הופעל עם startForegroundService()
 *     בלי לקרוא ל-startForeground, מה שגרם ל-ForegroundServiceDidNotStartInTime
 *     — קריסה מובטחת בכל מחזור Watchdog שבו ה-VPN לא רץ.
 *   • ללולאת ה-restart יש backoff ומכסה. קודם כשל ב-establish() גרר ניסיון
 *     חוזר כל 2 שניות לנצח, עם ניקוז סוללה מלא.
 */
public class MagenVpnService extends VpnService implements Runnable, TunBridge {

    private static final String TAG = "MagenVPN";

    private static final String CHANNEL_ID = "magen_vpn";
    private static final int    NOTIF_ID   = 1004;

    public static volatile boolean isVpnRunning = false;

    /** ספקי DNS ידועים — מנותבים פנימה גם במצב DNS-only כדי לחסום DoH/DoT. */
    private static final Set<String> KNOWN_RESOLVERS = new HashSet<>(Arrays.asList(
        "8.8.8.8", "8.8.4.4",
        "1.1.1.1", "1.0.0.1", "1.1.1.2", "1.0.0.2", "1.1.1.3", "1.0.0.3",
        "9.9.9.9", "149.112.112.112", "9.9.9.11", "149.112.112.11",
        "208.67.222.222", "208.67.220.220", "208.67.222.123", "208.67.220.123",
        "185.228.168.9", "185.228.169.9", "185.228.168.10", "185.228.169.11",
        "94.140.14.14", "94.140.15.15",
        "45.90.28.0", "45.90.30.0",
        "76.76.2.0", "76.76.10.0",
        "77.88.8.8", "77.88.8.1",
        "64.6.64.6", "64.6.65.6", "84.200.69.80", "84.200.70.40",
        "194.242.2.2", "193.110.81.0", "185.253.5.0"
    ));

    // backoff להקמה מחדש — לא לולאה של 2 שניות
    private static final long RESTART_BASE_MS      = 5_000L;
    private static final long RESTART_MAX_MS       = 5 * 60_000L;
    private static final int  RESTART_MAX_ATTEMPTS = 8;
    private static int restartAttempts = 0;

    private volatile boolean isRunning = false;
    private volatile boolean intentionalStop = false;

    private Thread vpnThread;
    private ParcelFileDescriptor vpnInterface;
    private volatile FileOutputStream tunOut;
    private final Object writeLock = new Object();
    private VpnEngine engine;

    // ---------------- מחזור חיים ----------------

    @Override
    public void onCreate() {
        super.onCreate();
        VpnPolicy.init(this);
        DomainVerdict.init(this);
        startAsForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // חייב להיקרא מיד — גם כשהופעלנו דרך startForegroundService()
        startAsForeground();

        intentionalStop = false;
        if (vpnThread == null || !vpnThread.isAlive()) {
            isRunning = true;
            vpnThread = new Thread(this, "MagenVpnThread");
            vpnThread.start();
        }
        return START_STICKY;
    }

    private void startAsForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "סינון רשת", NotificationManager.IMPORTANCE_MIN);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }

            Intent open = new Intent();
            open.setClassName(getPackageName(), getPackageName() + ".ui.MainActivity");
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

            Notification n = b
                .setContentTitle("סינון רשת פעיל")
                .setContentText(VpnPolicy.fullTunnel() ? "מצב מלא" : "מצב DNS")
                .setSmallIcon(com.magen.family.R.drawable.ic_notification)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();

            startForeground(NOTIF_ID, n);
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed: " + t.getMessage());
        }
    }

    // ---------------- לולאת המנהרה ----------------

    @Override
    public void run() {
        FileInputStream in = null;
        boolean establishedOk = false;

        try {
            vpnInterface = buildTunnel();
            if (vpnInterface == null) {
                Log.e(TAG, "establish() returned null — VPN permission missing?");
                return;
            }
            establishedOk = true;
            restartAttempts = 0;

            isVpnRunning = true;
            stopService(new Intent(this, MagenKillSwitch.class));

            in     = new FileInputStream(vpnInterface.getFileDescriptor());
            tunOut = new FileOutputStream(vpnInterface.getFileDescriptor());

            engine = new VpnEngine(this);
            engine.start();

            Log.d(TAG, "✓ VPN active | mode=" + (VpnPolicy.fullTunnel() ? "full" : "dns"));

            byte[] packet = new byte[32767];
            while (isRunning) {
                int len = in.read(packet);
                if (len < 0) break;                   // המנהרה נסגרה
                if (len == 0) continue;

                engine.processPacket(packet, len);

                // המנוע ביקש נסיגה — יוצאים כדי להקים מחדש במצב DNS-only
                if (engine.isDegraded()) {
                    Log.w(TAG, "engine degraded — restarting in DNS-only mode");
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "VPN loop error: " + e.getMessage());
        } finally {
            isVpnRunning = false;
            if (engine != null) { engine.stop(); engine = null; }
            closeAll(in);
            scheduleRestartIfNeeded(establishedOk);
        }
    }

    private ParcelFileDescriptor buildTunnel() {
        Builder builder = new Builder()
            .setSession("שומר הברית")
            .setMtu(1500)
            .addAddress("10.7.7.2", 32)
            .addDnsServer(VpnPolicy.upstreamDns())
            .addDnsServer(VpnPolicy.FALLBACK_UPSTREAM_DNS);

        if (VpnPolicy.fullTunnel()) {
            // כל תעבורת IPv4 עוברת דרכנו — זה מה שסוגר את חור ה-DNS השרירותי
            builder.addRoute("0.0.0.0", 0);
        } else {
            // רק ה-DNS שלנו וספקי ה-DNS הידועים
            safeRoute(builder, VpnPolicy.upstreamDns());
            safeRoute(builder, VpnPolicy.FALLBACK_UPSTREAM_DNS);
            for (String ip : KNOWN_RESOLVERS) safeRoute(builder, ip);
        }

        // IPv6 נכנס למנהרה ונזרק, אחרת הוא היה עוקף את הסינון לגמרי
        try {
            builder.addAddress("fd00:2:2::2", 128);
            builder.addRoute("::", 0);
        } catch (Exception e) {
            Log.w(TAG, "IPv6 blackhole not applied: " + e.getMessage());
        }

        // האפליקציה שלנו מחוץ למנהרה, אחרת עדכון הרשימות ייכנס ללולאה
        try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) {}

        try {
            return builder.establish();
        } catch (Exception e) {
            Log.e(TAG, "establish failed: " + e.getMessage());
            return null;
        }
    }

    private void safeRoute(Builder builder, String ip) {
        try { builder.addRoute(ip, 32); } catch (Exception ignored) {}
    }

    /**
     * הקמה מחדש עם backoff מעריכי.
     * קודם: כשל ב-establish() גרר ניסיון חוזר כל 2 שניות, לנצח.
     */
    private void scheduleRestartIfNeeded(boolean establishedOk) {
        if (intentionalStop) return;

        if (!establishedOk) {
            restartAttempts++;
            if (restartAttempts > RESTART_MAX_ATTEMPTS) {
                Log.e(TAG, "giving up restart after " + restartAttempts + " attempts");
                try {
                    NotificationHelper.notifyUrgent(this,
                        "⚠️ סינון הרשת לא מצליח לעלות. ייתכן שהרשאת ה-VPN בוטלה.");
                } catch (Exception ignored) {}
                return;
            }
        }

        long delay = Math.min(RESTART_BASE_MS * (1L << Math.min(restartAttempts, 6)),
                              RESTART_MAX_MS);
        Log.d(TAG, "restarting in " + delay + "ms (attempt " + restartAttempts + ")");

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try { startService(new Intent(this, MagenVpnService.class)); }
            catch (Exception ignored) {}
        }, delay);
    }

    // ---------------- TunBridge ----------------

    @Override
    public boolean protect(Socket socket) {
        return super.protect(socket);
    }

    @Override
    public boolean protect(DatagramSocket socket) {
        return super.protect(socket);
    }

    @Override
    public void writeToTun(byte[] packet, int length) {
        FileOutputStream out = tunOut;
        if (out == null) return;
        try {
            synchronized (writeLock) {
                out.write(packet, 0, length);
            }
        } catch (Exception e) {
            Log.w(TAG, "writeToTun failed: " + e.getMessage());
        }
    }

    @Override
    public Context context() {
        return getApplicationContext();
    }

    // ---------------- כיבוי ----------------

    private void closeAll(FileInputStream in) {
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        synchronized (writeLock) {
            try { if (tunOut != null) tunOut.close(); } catch (Exception ignored) {}
            tunOut = null;
        }
        try {
            if (vpnInterface != null) { vpnInterface.close(); vpnInterface = null; }
        } catch (Exception ignored) {}
    }

    /**
     * onRevoke נקרא בדיוק כשמפעילים VPN אחר (למשל 1.1.1.1) — אנדרואיד מרשה
     * VPN אחד בלבד, אז שלנו מבוטל והסינון מת. אי אפשר למנוע את זה ב-Device
     * Admin, אבל אפשר להפוך את זה ל"יקר ורועש": נועלים את מסך המערכת, מתריעים
     * לשרת, ורושמים שבירת רצף. השומר (TamperDetector/Watchdog) ינסה להחזיר
     * את ה-VPN שלנו ברגע שה-VPN החיצוני יכובה.
     */
    @Override
    public void onRevoke() {
        // לא מסמנים intentionalStop — אנחנו *כן* רוצים לנסות לחזור
        isRunning = false;
        isVpnRunning = false;
        if (vpnThread != null) vpnThread.interrupt();
        try {
            com.magen.family.admin.MagenDeviceAdmin.lockDeviceNow(this);
            NotificationHelper.notifyUrgent(this,
                "🚨 הופעל VPN אחר — סינון הרשת של שומר הברית בוטל! ייתכן ניסיון עקיפה.");
            ServerEventReporter.report(this, "VPN_REVOKED", "CRITICAL",
                "Android revoked Magen VPN, likely another VPN was prepared");
            // מיד אחרי revoke בודקים אילו אפליקציות VPN מותקנות. במצב Device
            // Owner הן גם מושעות/מוסתרות; במצב רגיל הן נכנסות לרשימת החסימה.
            new Thread(() -> AppInstallReceiver.enforceInstalledVpnApps(getApplicationContext()),
                "VpnRevokeAudit").start();
            ActivityReporter.recordSecurityAlert(this);
            com.magen.family.covenant.StreakManager.recordSlip(this, "VPN חיצוני");
        } catch (Exception ignored) {}
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        intentionalStop = true;
        isRunning = false;
        isVpnRunning = false;
        if (engine != null) { engine.stop(); engine = null; }
        if (vpnThread != null) vpnThread.interrupt();
        super.onDestroy();
    }
}
