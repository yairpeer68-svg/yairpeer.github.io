package com.magen.family.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.magen.family.MagenApp;
import com.magen.family.ui.MainActivity;

/**
 * NotificationHelper — שולח להורה התראות חכמות:
 *   • התראה לוקלית (תמיד עובדת, לא תלויה ב-SMS permission).
 *   • SMS *רק* אם:
 *       - ההורה מילא טלפון
 *       - יש הרשאת SEND_SMS אמיתית (לא תיאורטית)
 *       - האירוע באמת דחוף (uninstall attempt, ספייק חמור)
 *   • Digest: מצרף אירועים קטנים להודעה אחת.
 */
public class NotificationHelper {

    private static final String TAG = "NotificationHelper";
    private static final String CHANNEL_URGENT = "magen_alerts_urgent";
    private static final String CHANNEL_DIGEST = "magen_alerts_digest";
    private static final int NOTIF_ID_URGENT = 2001;
    private static final int NOTIF_ID_DIGEST = 2002;

    /**
     * התראה דחופה: ניסיון הסרה, root, צ׳יזיט קריטי. תמיד נשלחת — SMS + local.
     */
    public static void notifyPartnerUrgent(Context ctx, String message) {
        showLocalNotification(ctx, CHANNEL_URGENT, NOTIF_ID_URGENT,
            "🚨 התראה דחופה", message, true);

        // מגן אחרון מפני הצפת SMS: גם אם קורא כלשהו שכח throttle משלו,
        // לא יישלחו יותר מהודעה אחת בכל MIN_SMS_INTERVAL_MS. SMS עולה כסף
        // ולא ניתן לבטל אותו אחרי שנשלח.
        long now = System.currentTimeMillis();
        synchronized (NotificationHelper.class) {
            if (now - lastSmsAt < MIN_SMS_INTERVAL_MS) {
                Log.d(TAG, "SMS throttled");
                return;
            }
            lastSmsAt = now;
        }
        // טלגרם — ללא עלות וללא שרת. נשלח תמיד כשמוגדר.
        TelegramNotifier.send(ctx, "🚨 שומר הברית: " + message);

        trySendSms(ctx, "🚨 שומר הברית: " + message);
    }

    /** לכל היותר SMS אחד ברבע שעה, בכל האפליקציה. */
    private static final long MIN_SMS_INTERVAL_MS = 15 * 60 * 1000L;
    private static long lastSmsAt = 0;

    /**
     * Digest — אירועים מצטברים. התראה לוקלית + טלגרם (לא SMS, כדי לא לבזבז).
     */
    public static void notifyPartnerDigest(Context ctx, String digest) {
        String summary = "התרחשו אירועים בשעה האחרונה";
        showLocalNotification(ctx, CHANNEL_DIGEST, NOTIF_ID_DIGEST,
            "📋 סיכום פעילות", digest != null ? digest : summary, false);
        TelegramNotifier.send(ctx, "📋 שומר הברית — סיכום:\n" + (digest != null ? digest : summary));
    }

    /** התראה על עדכון זמין — לחיצה פותחת את קישור ההורדה. */
    public static void notifyUpdateAvailable(Context ctx, String versionName, String apkUrl) {
        ensureChannel(ctx, CHANNEL_DIGEST, false);
        try {
            Intent open = (apkUrl != null && !apkUrl.isEmpty())
                ? new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(apkUrl))
                : new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 3, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_DIGEST)
                .setSmallIcon(com.magen.family.R.drawable.ic_notification)
                .setContentTitle("עדכון זמין" + (versionName.isEmpty() ? "" : " — " + versionName))
                .setContentText("לחץ להורדת הגרסה החדשה")
                .setContentIntent(pi)
                .setAutoCancel(true);

            NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(2003, b.build());
        } catch (Exception e) {
            Log.e(TAG, "notifyUpdate failed: " + e.getMessage());
        }
    }

    // ---------------------- Internals ----------------------

    private static void showLocalNotification(Context ctx, String channelId,
                                              int notifId, String title,
                                              String body, boolean urgent) {
        ensureChannel(ctx, channelId, urgent);

        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(com.magen.family.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(urgent
                ? NotificationCompat.PRIORITY_HIGH
                : NotificationCompat.PRIORITY_DEFAULT);

        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try { nm.notify(notifId, b.build()); }
            catch (Exception e) { Log.e(TAG, "notify failed: " + e.getMessage()); }
        }
    }

    private static void ensureChannel(Context ctx, String id, boolean urgent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (nm.getNotificationChannel(id) != null) return;
        NotificationChannel ch = new NotificationChannel(id,
            urgent ? "התראות דחופות" : "סיכומי פעילות",
            urgent ? NotificationManager.IMPORTANCE_HIGH
                   : NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("התראות הורה — שומר הברית");
        nm.createNotificationChannel(ch);
    }

    private static void trySendSms(Context ctx, String message) {
        String parentPhone = MagenApp.getInstance().getPrefs()
            .getString(MagenApp.KEY_PARENT_PHONE, "");
        if (parentPhone == null || parentPhone.isEmpty()) return;

        // SMS_PERMISSION בדיקה אמיתית — לא נסתפק ב-try/catch
        boolean hasPerm = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
        if (!hasPerm) {
            Log.w(TAG, "SMS permission missing — skipping SMS");
            return;
        }

        try {
            android.telephony.SmsManager sms;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sms = ctx.getSystemService(android.telephony.SmsManager.class);
            } else {
                sms = android.telephony.SmsManager.getDefault();
            }
            // פיצול אוטומטי להודעות באורך תקני
            java.util.ArrayList<String> parts = sms.divideMessage(message);
            sms.sendMultipartTextMessage(parentPhone, null, parts, null, null);
            Log.d(TAG, "SMS sent");
        } catch (Exception e) {
            Log.e(TAG, "SMS failed: " + e.getMessage());
        }
    }
}
