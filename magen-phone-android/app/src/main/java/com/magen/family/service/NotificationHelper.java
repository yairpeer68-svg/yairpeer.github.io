package com.magen.family.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.magen.family.ui.MainActivity;

/**
 * NotificationHelper — התראה מקומית + דיווח חתום לשרת Magen.
 * כל התראות האבטחה נרשמות בשרת Magen; אין credentials של שירות התראות חיצוני על הטלפון.
 */
public class NotificationHelper {

    private static final String TAG = "NotificationHelper";
    private static final String CHANNEL_URGENT = "magen_alerts_urgent";
    private static final String CHANNEL_DIGEST = "magen_alerts_digest";
    private static final int NOTIF_ID_URGENT = 2001;
    private static final int NOTIF_ID_DIGEST = 2002;

    /**
     * התראה דחופה: מוצגת מקומית ונשלחת לשרת Magen.
     */
    public static void notifyUrgent(Context ctx, String message) {
        showLocalNotification(ctx, CHANNEL_URGENT, NOTIF_ID_URGENT,
            "🚨 התראה דחופה", message, true);

        // המקור המרכזי להתראות הוא ה-VPS; הטלפון מחזיק רק זהות מכשיר ומפתח חתימה מקומי.
        com.magen.family.server.ServerEventReporter.report(ctx,
            "URGENT_ALERT", "HIGH", message);
    }


    /**
     * Digest — אירועים מצטברים. התראה מקומית + אירוע חתום לשרת.
     */
    public static void notifyDigest(Context ctx, String digest) {
        String summary = "התרחשו אירועים בשעה האחרונה";
        showLocalNotification(ctx, CHANNEL_DIGEST, NOTIF_ID_DIGEST,
            "📋 סיכום פעילות", digest != null ? digest : summary, false);
        com.magen.family.server.ServerEventReporter.report(ctx,
            "ACTIVITY_DIGEST", "INFO", digest != null ? digest : summary);
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
        ch.setDescription("התראות אבטחה — שומר הברית");
        nm.createNotificationChannel(ch);
    }

}
