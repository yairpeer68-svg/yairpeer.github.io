package com.magen.family.service;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.magen.family.MagenApp;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * GeofenceService — חסימה לפי מיקום (למשל strict mode בבית הספר 8:00-15:00).
 *
 * מבנה נתונים ב-SharedPreferences ("geofences"):
 *   [
 *     {
 *       "name": "בית ספר",
 *       "lat": 32.0853, "lng": 34.7818,
 *       "radius_m": 200,
 *       "policy": "strict",          // strict / educational_only / block
 *       "hour_start": 8, "hour_end": 15,
 *       "days": [1,2,3,4,5]          // יום-שישי
 *     }
 *   ]
 *
 * שימוש: רץ ברקע בדרישה נמוכה (LocationManager עם min-time גבוה),
 * מפעיל strict mode כשהילד בתוך geofence פעיל.
 */
public class GeofenceService extends Service implements LocationListener {

    private static final String TAG = "Geofence";
    private static final String PREFS_KEY = "geofences";

    private static final long MIN_TIME_MS    = 5 * 60 * 1000L; // עדכון כל 5 דק
    private static final float MIN_DIST_M    = 50f;

    private LocationManager lm;
    private boolean tracking = false;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startTracking();
        return START_STICKY;
    }

    private void startTracking() {
        if (tracking) return;
        String configured = MagenApp.getInstance().getPrefs().getString(PREFS_KEY, "");
        if (configured == null || configured.trim().isEmpty() || "[]".equals(configured.trim())) return;

        if (ActivityCompat.checkSelfPermission(this,
            Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Precise location permission missing");
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q
            && ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Background location permission missing");
            return;
        }

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;

        try {
            // GPS אם זמין, אחרת network
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS, MIN_DIST_M, this);
                tracking = true;
            } else if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS, MIN_DIST_M, this);
                tracking = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "requestLocationUpdates: " + e.getMessage());
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (loc == null) return;
        evaluateGeofences(loc);
    }

    @Override public void onProviderEnabled(String p)  {}
    @Override public void onProviderDisabled(String p) {}
    @Override public void onStatusChanged(String p, int s, Bundle b) {}

    /**
     * בדוק האם הילד נמצא ב-geofence פעיל כעת.
     */
    private void evaluateGeofences(Location loc) {
        String raw = MagenApp.getInstance().getPrefs().getString(PREFS_KEY, "");
        if (raw.isEmpty()) return;

        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject g = arr.getJSONObject(i);
                double lat = g.getDouble("lat");
                double lng = g.getDouble("lng");
                float radius = (float) g.optDouble("radius_m", 200);

                float[] result = new float[1];
                Location.distanceBetween(loc.getLatitude(), loc.getLongitude(),
                    lat, lng, result);
                boolean inside = result[0] <= radius;
                if (!inside) continue;

                // בדוק חלון זמן
                int hour = java.util.Calendar.getInstance()
                    .get(java.util.Calendar.HOUR_OF_DAY);
                int day  = java.util.Calendar.getInstance()
                    .get(java.util.Calendar.DAY_OF_WEEK);

                int hourStart = g.optInt("hour_start", -1);
                int hourEnd   = g.optInt("hour_end",   -1);
                boolean inWindow = (hourStart < 0)
                    || (hour >= hourStart && hour < hourEnd);
                if (!inWindow) continue;

                if (g.has("days")) {
                    JSONArray days = g.getJSONArray("days");
                    boolean dayMatch = false;
                    for (int j = 0; j < days.length(); j++) {
                        if (days.getInt(j) == day) { dayMatch = true; break; }
                    }
                    if (!dayMatch) continue;
                }

                // הפעל policy
                String policy = g.optString("policy", "strict");
                applyPolicy(g.optString("name", "Zone"), policy);
                return;
            }

            // לא נמצא בשום geofence פעיל — בטל strict mode זמני
            MagenApp.getInstance().getPrefs().edit()
                .putBoolean("geofence_active", false).apply();
        } catch (Exception e) {
            Log.e(TAG, "evaluateGeofences: " + e.getMessage());
        }
    }

    private void applyPolicy(String zoneName, String policy) {
        Log.d(TAG, "Entered zone '" + zoneName + "' — policy=" + policy);
        MagenApp.getInstance().getPrefs().edit()
            .putBoolean("geofence_active", true)
            .putString("geofence_active_name", zoneName)
            .putString("geofence_active_policy", policy)
            .apply();

        if ("block".equals(policy)) {
            // חסימה מוחלטת — KillSwitch
            MagenKillSwitch.start(this, new Intent(this, MagenKillSwitch.class));
        } else if ("strict".equals(policy)) {
            // strict mode — נשלף ע"י BehaviorAnalyzer.isStrictMode()
            // כאן מגדירים strict_until לעוד שעה
            long until = System.currentTimeMillis() + 60 * 60 * 1000L;
            MagenApp.getInstance().getPrefs().edit()
                .putLong("strict_mode_until", until).apply();
        }
    }

    @Override
    public void onDestroy() {
        if (lm != null) {
            try { lm.removeUpdates(this); } catch (Exception ignored) {}
        }
        tracking = false;
        super.onDestroy();
    }
}
