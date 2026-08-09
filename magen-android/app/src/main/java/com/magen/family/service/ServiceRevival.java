package com.magen.family.service;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.util.Log;

/**
 * ServiceRevival — נקודה מרכזית אחת שמקימה מחדש את כל רכיבי ההגנה.
 *
 * נקראת מכל השומרים: Watchdog (כל 15 דק'), TamperDetector (כל 30 שנ'),
 * BootReceiver (אחרי אתחול/עדכון), ומ-onServiceConnected של שירות הנגישות.
 * כך כל רכיב שנפל מוקם בחזרה על ידי אחד השומרים — "החייאה הדדית".
 *
 * הערה: שירות נגישות *אי אפשר* להפעיל פרוגרמטית (הגבלת אנדרואיד) — רק
 * המשתמש מפעיל אותו בהגדרות. לכן כאן מטפלים ב-VPN + מוודאים שהשומרים חיים,
 * ואם הנגישות כבויה — מתריעים ונועלים (זה מטופל ב-TamperDetector).
 */
public final class ServiceRevival {

    private static final String TAG = "ServiceRevival";
    private static volatile long lastRun = 0;

    private ServiceRevival() {}

    /** מקים את כל מה שאפשר להקים. מוגן מפני קריאות תכופות מדי. */
    public static void reviveAll(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastRun < 3000) return;   // דה-באונס
        lastRun = now;

        reviveVpn(ctx);
        rescheduleWatchdog(ctx);
        ensureTamperRunning(ctx);
        ensureWatchers(ctx);
    }

    /** מפעיל את ה-VPN אם ההרשאה קיימת והוא לא רץ. */
    public static void reviveVpn(Context ctx) {
        try {
            if (MagenVpnService.isVpnRunning) return;
            // אם המשתמש עדיין לא אישר VPN — prepare יחזיר Intent (צריך UI)
            Intent prep = VpnService.prepare(ctx);
            if (prep != null) {
                Log.w(TAG, "VPN not authorized yet — needs user consent");
                return;
            }
            Intent svc = new Intent(ctx, MagenVpnService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
            Log.d(TAG, "VPN revive requested");
        } catch (Exception e) {
            Log.e(TAG, "reviveVpn failed: " + e.getMessage());
        }
    }

    private static void rescheduleWatchdog(Context ctx) {
        try {
            MagenWatchdogJob.schedule(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "watchdog reschedule skipped: " + t.getMessage());
        }
    }

    /**
     * TamperDetectorService אינו foreground service ולכן חייבים startService רגיל.
     * קודם השתמשו כאן ב-startForegroundService(), וזו הבטחה למערכת שהשירות
     * יקרא ל-startForeground() תוך 5 שניות. הוא לא קרא — ולכן אנדרואיד זרק
     * ForegroundServiceDidNotStartInTimeException והאפליקציה קרסה.
     * התהליך ממילא נשאר חי בזכות FilterService ו-MagenVpnService שהם foreground.
     */
    private static void ensureTamperRunning(Context ctx) {
        try {
            ctx.startService(new Intent(ctx, TamperDetectorService.class));
        } catch (Exception e) {
            Log.w(TAG, "tamper ensure skipped: " + e.getMessage());
        }
    }

    /** מפעיל את הזיהוי המיידי (ContentObserver) על הגדרות רגישות. */
    public static void ensureWatchers(Context ctx) {
        try {
            TamperWatcher.start(ctx);
        } catch (Exception e) {
            Log.w(TAG, "watcher start skipped: " + e.getMessage());
        }
    }
}
