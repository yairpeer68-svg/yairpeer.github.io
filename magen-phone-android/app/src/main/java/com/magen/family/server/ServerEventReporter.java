package com.magen.family.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reliable best-effort event delivery to the private Magen VPS.
 *
 * Local protection never waits for the network. If the VPS is temporarily unavailable,
 * security events are kept in a small on-device queue and retried on the next event or
 * heartbeat. The queue is intentionally bounded so a broken network cannot grow storage
 * without limit.
 */
public final class ServerEventReporter {
    private static final String TAG = "MagenEvents";
    private static final String PREFS = "magen_event_queue";
    private static final String K_PENDING = "pending_v1";
    private static final int MAX_PENDING = 50;
    private static final Object LOCK = new Object();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MagenEvents");
        t.setDaemon(true);
        return t;
    });

    private ServerEventReporter() {}

    public static void report(Context c, String type, String severity, String detail) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        final JSONObject event = build(type, severity, detail);
        EXEC.execute(() -> {
            synchronized (LOCK) {
                flushLocked(app);
                if (!send(app, event)) enqueueLocked(app, event);
            }
        });
    }

    /** Trigger a retry after connectivity/server recovery without creating a new event. */
    public static void flushPendingAsync(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        EXEC.execute(() -> {
            synchronized (LOCK) { flushLocked(app); }
        });
    }

    private static JSONObject build(String type, String severity, String detail) {
        JSONObject details = new JSONObject();
        JSONObject event = new JSONObject();
        try {
            String d = detail == null ? "" : detail;
            if (d.length() > 1000) d = d.substring(0, 1000);
            if (!d.isEmpty()) details.put("detail", d);
            event.put("event_type", sanitize(type, "UNKNOWN", 64));
            event.put("severity", sanitize(severity, "INFO", 16).toUpperCase(java.util.Locale.ROOT));
            event.put("details", details);
            event.put("client_time_ms", System.currentTimeMillis());
        } catch (Exception ignored) {}
        return event;
    }

    private static String sanitize(String value, String fallback, int max) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) v = fallback;
        return v.substring(0, Math.min(max, v.length()));
    }

    private static boolean send(Context app, JSONObject event) {
        if (!ServerConfig.ready(app)) return false;
        try {
            // Server schema intentionally receives only the documented fields.
            JSONObject wire = new JSONObject()
                .put("event_type", event.optString("event_type", "UNKNOWN"))
                .put("severity", event.optString("severity", "INFO"))
                .put("details", event.optJSONObject("details") != null
                    ? event.optJSONObject("details") : new JSONObject());
            MagenApiClient.signedPost(app, "/v1/events", wire, false);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "event delivery failed: " + e.getMessage());
            return false;
        }
    }

    private static void flushLocked(Context app) {
        if (!ServerConfig.ready(app)) return;
        JSONArray pending = load(app);
        if (pending.length() == 0) return;

        JSONArray remaining = new JSONArray();
        boolean failed = false;
        for (int i = 0; i < pending.length(); i++) {
            JSONObject e = pending.optJSONObject(i);
            if (e == null) continue;
            if (!failed && send(app, e)) continue;
            failed = true;
            remaining.put(e);
        }
        save(app, remaining);
    }

    private static void enqueueLocked(Context app, JSONObject event) {
        JSONArray old = load(app);
        JSONArray next = new JSONArray();
        int start = Math.max(0, old.length() - (MAX_PENDING - 1));
        for (int i = start; i < old.length(); i++) {
            JSONObject e = old.optJSONObject(i);
            if (e != null) next.put(e);
        }
        next.put(event);
        save(app, next);
    }

    private static JSONArray load(Context app) {
        try {
            SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String raw = p.getString(K_PENDING, "[]");
            JSONArray a = new JSONArray(raw == null ? "[]" : raw);
            if (a.length() <= MAX_PENDING) return a;
            JSONArray trimmed = new JSONArray();
            for (int i = a.length() - MAX_PENDING; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e != null) trimmed.put(e);
            }
            return trimmed;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void save(Context app, JSONArray a) {
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(K_PENDING, a.toString()).apply();
    }
}
