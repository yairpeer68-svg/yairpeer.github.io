package com.magen.family.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reliable bounded event delivery to the private Magen VPS. */
public final class ServerEventReporter {
    private static final String TAG = "MagenEvents";
    private static final String PREFS = "magen_event_queue";
    private static final String K_PENDING = "pending_v1";
    private static final int MAX_PENDING = 100;
    private static final int MAX_DETAILS_CHARS = 6500;
    private static final Object LOCK = new Object();
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MagenEvents"); t.setDaemon(true); return t;
    });
    private ServerEventReporter() {}

    public static void report(Context c, String type, String severity, String detail) {
        JSONObject d = new JSONObject();
        try { if (detail != null && !detail.isEmpty()) d.put("detail", trim(detail, 1500)); } catch (Exception ignored) {}
        report(c, type, severity, d);
    }

    public static void report(Context c, String type, String severity, JSONObject details) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        final JSONObject event = build(type, severity, details);
        EXEC.execute(() -> { synchronized (LOCK) { flushLocked(app); if (!send(app,event)) enqueueLocked(app,event,false); } });
    }

    /**
     * Crash-safe path: persist synchronously before the dying process delegates to Android's
     * default uncaught-exception handler. Network I/O is intentionally not attempted here.
     */
    public static void enqueueForLaterSync(Context c, String type, String severity, JSONObject details) {
        if (c == null) return;
        synchronized (LOCK) { enqueueLocked(c.getApplicationContext(), build(type,severity,details), true); }
    }

    public static void flushPendingAsync(Context c) {
        if (c == null) return;
        final Context app = c.getApplicationContext();
        EXEC.execute(() -> { synchronized (LOCK) { flushLocked(app); } });
    }

    private static JSONObject build(String type, String severity, JSONObject input) {
        JSONObject details = sanitizeDetails(input);
        JSONObject event = new JSONObject();
        try {
            if (!details.has("client_time_ms")) details.put("client_time_ms", System.currentTimeMillis());
            event.put("client_event_id", UUID.randomUUID().toString());
            event.put("event_type", sanitize(type,"UNKNOWN",64));
            event.put("severity", sanitize(severity,"INFO",16).toUpperCase(java.util.Locale.ROOT));
            event.put("details", details);
        } catch (Exception ignored) {}
        return event;
    }

    private static JSONObject sanitizeDetails(JSONObject src) {
        JSONObject out = new JSONObject();
        if (src == null) return out;
        try {
            Iterator<String> keys = src.keys();
            while (keys.hasNext()) {
                String original = keys.next();
                String k = sanitize(original,"field",64);
                Object v = src.opt(original);
                if (v == null || v == JSONObject.NULL) continue;
                if (v instanceof Number || v instanceof Boolean) out.put(k,v);
                else out.put(k, trim(String.valueOf(v), 4000));
            }
            if (out.toString().length() > MAX_DETAILS_CHARS) {
                String stack = out.optString("stack","");
                if (!stack.isEmpty()) out.put("stack", trim(stack, 1800));
                String desc = out.optString("description","");
                if (!desc.isEmpty()) out.put("description", trim(desc, 500));
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String trim(String s,int max){ if(s==null)return ""; return s.substring(0,Math.min(max,s.length())); }
    private static String sanitize(String value,String fallback,int max){ String v=value==null?"":value.trim(); if(v.isEmpty())v=fallback; return v.substring(0,Math.min(max,v.length())); }

    private static boolean send(Context app, JSONObject event) {
        if (!ServerConfig.ready(app)) return false;
        try {
            JSONObject wire = new JSONObject()
                .put("client_event_id",event.optString("client_event_id",""))
                .put("event_type",event.optString("event_type","UNKNOWN"))
                .put("severity",event.optString("severity","INFO"))
                .put("details",event.optJSONObject("details")!=null?event.optJSONObject("details"):new JSONObject());
            MagenApiClient.signedPost(app,"/v1/events",wire,false); return true;
        } catch (Exception e) { Log.w(TAG,"event delivery failed: "+e.getClass().getSimpleName()); return false; }
    }

    private static void flushLocked(Context app) {
        if (!ServerConfig.ready(app)) return;
        JSONArray pending=load(app); if(pending.length()==0)return;
        JSONArray remaining=new JSONArray(); boolean failed=false;
        for(int i=0;i<pending.length();i++){ JSONObject e=pending.optJSONObject(i); if(e==null)continue; if(!failed&&send(app,e))continue; failed=true; remaining.put(e); }
        save(app,remaining,false);
    }

    private static void enqueueLocked(Context app, JSONObject event, boolean sync) {
        JSONArray old=load(app), next=new JSONArray();
        int start=Math.max(0,old.length()-(MAX_PENDING-1));
        for(int i=start;i<old.length();i++){ JSONObject e=old.optJSONObject(i); if(e!=null)next.put(e); }
        next.put(event); save(app,next,sync);
    }

    private static JSONArray load(Context app) {
        try {
            SharedPreferences p=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
            String raw=p.getString(K_PENDING,"[]"); JSONArray a=new JSONArray(raw==null?"[]":raw);
            if(a.length()<=MAX_PENDING)return a;
            JSONArray t=new JSONArray(); for(int i=a.length()-MAX_PENDING;i<a.length();i++){JSONObject e=a.optJSONObject(i);if(e!=null)t.put(e);} return t;
        } catch(Exception e){ return new JSONArray(); }
    }

    private static void save(Context app, JSONArray a, boolean sync) {
        SharedPreferences.Editor ed=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(K_PENDING,a.toString());
        if(sync) ed.commit(); else ed.apply();
    }
}
