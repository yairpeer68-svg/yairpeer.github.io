package com.magen.family.service;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * UpdateChecker — בדיקת עדכון גרסה מול קובץ סטטי (GitHub Releases/Raw).
 *
 * למה בלי שרת:
 *   אפליקציה ב-sideload לא מקבלת עדכוני חנות, ובלי מנגנון עדכון תיקוני
 *   אבטחה לא מגיעים. אירוח קובץ JSON קטן ב-GitHub (Raw או Release asset)
 *   הוא אחסון סטטי — לא שרת שצריך לתחזק.
 *
 * פורמט הקובץ שה-URL מצביע עליו:
 *   { "versionCode": 5, "versionName": "2.1", "url": "https://.../app.apk",
 *     "notes": "מה חדש" }
 *
 * כבוי כברירת מחדל (URL ריק). המשתמש/המפתח מגדיר URL משלו.
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String PREFS = "magen_update";
    private static final String K_URL = "manifest_url";
    private static final String K_LAST_CHECK = "last_check";

    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final int TIMEOUT_MS = 12000;

    private UpdateChecker() {}

    public static void setManifestUrl(Context ctx, String url) {
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(K_URL, url.trim()).apply();
    }

    public static String getManifestUrl(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_URL, "");
    }

    /** בדיקה תקופתית ברקע — נקראת מה-Watchdog. */
    public static void checkIfDue(Context ctx) {
        String url = getManifestUrl(ctx);
        if (url.isEmpty()) return;

        android.content.SharedPreferences p = ctx.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong(K_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;

        new Thread(() -> {
            try {
                checkNow(ctx, url);
                p.edit().putLong(K_LAST_CHECK, System.currentTimeMillis()).apply();
            } catch (Exception e) {
                Log.w(TAG, "check failed: " + e.getMessage());
            }
        }, "UpdateCheck").start();
    }

    private static void checkNow(Context ctx, String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if (conn.getResponseCode() != 200) return;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            int remoteCode = o.optInt("versionCode", 0);

            int localCode = ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), 0).versionCode;

            if (remoteCode > localCode) {
                String name = o.optString("versionName", "");
                String apkUrl = o.optString("url", "");
                NotificationHelper.notifyUpdateAvailable(ctx, name, apkUrl);
                Log.d(TAG, "update available: " + name);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
