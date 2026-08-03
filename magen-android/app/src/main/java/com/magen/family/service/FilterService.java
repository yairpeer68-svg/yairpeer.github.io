package com.magen.family.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.magen.family.ui.MainActivity;

public class FilterService extends Service {

    private static final String TAG = "FilterService";
    private static final String CHANNEL_ID = "magen_filter_v3";
    private static final int NOTIFICATION_ID = 1003;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, build());
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed: " + t.getMessage(), t);
        }
        return START_STICKY;
    }

    private Notification build() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(com.magen.family.R.drawable.ic_notification);
        b.setContentTitle("שומר הברית");
        b.setContentText("הגנה פעילה");
        b.setOngoing(true);
        b.setContentIntent(pi);
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                "שומר הברית", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
