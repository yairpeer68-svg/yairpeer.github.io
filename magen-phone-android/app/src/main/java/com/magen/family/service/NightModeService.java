package com.magen.family.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;
import com.magen.family.MagenApp;
import java.util.Calendar;

/**
 * מצב לילה — חסימה מוחלטת בשעות שינה
 */
public class NightModeService extends Service {

    private static final String TAG = "NightMode";
    public static final String ACTION_CHECK = "com.magen.family.NIGHT_CHECK";

    private BroadcastReceiver receiver;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        checkNightMode();
        return START_STICKY;
    }

    private void checkNightMode() {
        MagenApp app = MagenApp.getInstance();
        if (app == null) return;

        boolean nightEnabled = app.getPrefs().getBoolean("night_mode_enabled", false);
        if (!nightEnabled) {
            stopMagenKillSwitch();
            return;
        }

        int startHour = app.getPrefs().getInt("night_start_hour", 22);
        int endHour   = app.getPrefs().getInt("night_end_hour", 7);

        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);

        boolean isNight;
        if (startHour > endHour) {
            // לילה עובר חצות (22:00-07:00)
            isNight = hour >= startHour || hour < endHour;
        } else {
            isNight = hour >= startHour && hour < endHour;
        }

        Log.d(TAG, "Hour: " + hour + " | Night: " + isNight);

        if (isNight) {
            // הצג KillSwitch
            MagenKillSwitch.start(this, new Intent(this, MagenKillSwitch.class));
            Log.d(TAG, "🌙 Night mode active");
        } else {
            stopMagenKillSwitch();
        }
    }

    private void stopMagenKillSwitch() {
        stopService(new Intent(this, MagenKillSwitch.class));
    }

    /**
     * הגדרת AlarmManager לבדיקה כל שעה
     */
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(context, NightModeService.class);
        PendingIntent pi = PendingIntent.getService(context, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        if (am != null) {
            am.setRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis(),
                AlarmManager.INTERVAL_HOUR,
                pi);
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
