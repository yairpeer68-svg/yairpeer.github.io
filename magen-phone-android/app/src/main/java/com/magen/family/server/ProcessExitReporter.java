package com.magen.family.server;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Uploads Android's own process-exit diagnostics on the next process start (API 30+). */
public final class ProcessExitReporter {
    private static final String PREFS = "magen_exit_telemetry";
    private static final String K_LAST_TS = "last_exit_timestamp_v1";
    private ProcessExitReporter() {}

    public static void collectAsync(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(() -> collect(app), "MagenExitReporter");
        t.setDaemon(true);
        t.start();
    }

    private static void collect(Context app) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            List<ApplicationExitInfo> src = am.getHistoricalProcessExitReasons(app.getPackageName(), 0, 12);
            if (src == null || src.isEmpty()) return;
            ArrayList<ApplicationExitInfo> infos = new ArrayList<>(src);
            Collections.sort(infos, (a,b) -> Long.compare(a.getTimestamp(), b.getTimestamp()));

            SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long last = p.getLong(K_LAST_TS, 0L);
            long newest = last;
            for (ApplicationExitInfo info : infos) {
                long ts = info.getTimestamp();
                if (ts <= last) continue;
                newest = Math.max(newest, ts);
                if (!isDiagnosticReason(info.getReason())) continue;

                JSONObject d = new JSONObject();
                d.put("reason", reasonName(info.getReason()));
                d.put("reason_code", info.getReason());
                d.put("timestamp_ms", ts);
                d.put("status", info.getStatus());
                d.put("importance", info.getImportance());
                d.put("pss_kb", Math.max(0L, info.getPss()));
                d.put("rss_kb", Math.max(0L, info.getRss()));
                d.put("process", safe(info.getProcessName(), 160));
                d.put("description", safe(info.getDescription(), 700));
                if (info.getReason() == ApplicationExitInfo.REASON_ANR) {
                    String trace = readAnrTrace(info);
                    if (!trace.isEmpty()) d.put("trace", trace);
                }
                ServerEventReporter.enqueueForLaterSync(app, "PROCESS_EXIT", severity(info.getReason()), d);
            }
            if (newest > last) p.edit().putLong(K_LAST_TS, newest).commit();
            ServerEventReporter.flushPendingAsync(app);
        } catch (Throwable ignored) {}
    }


    private static String readAnrTrace(ApplicationExitInfo info) {
        try (InputStream in=info.getTraceInputStream()) {
            if (in==null) return "";
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buf=new byte[1024]; int total=0,n;
            while(total<4500 && (n=in.read(buf,0,Math.min(buf.length,4500-total)))>0){ out.write(buf,0,n); total+=n; }
            return safe(new String(out.toByteArray(),StandardCharsets.UTF_8),4500);
        } catch(Exception e){ return ""; }
    }

    private static boolean isDiagnosticReason(int r) {
        return r == ApplicationExitInfo.REASON_CRASH
            || r == ApplicationExitInfo.REASON_CRASH_NATIVE
            || r == ApplicationExitInfo.REASON_ANR
            || r == ApplicationExitInfo.REASON_LOW_MEMORY
            || r == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
            || r == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
            || r == ApplicationExitInfo.REASON_DEPENDENCY_DIED
            || r == ApplicationExitInfo.REASON_PERMISSION_CHANGE
            || r == ApplicationExitInfo.REASON_SIGNALED;
    }

    private static String severity(int r) {
        if (r == ApplicationExitInfo.REASON_CRASH || r == ApplicationExitInfo.REASON_CRASH_NATIVE
            || r == ApplicationExitInfo.REASON_ANR || r == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
            return "CRITICAL";
        if (r == ApplicationExitInfo.REASON_LOW_MEMORY || r == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
            || r == ApplicationExitInfo.REASON_SIGNALED) return "HIGH";
        return "MEDIUM";
    }

    private static String reasonName(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_CRASH: return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            default: return "OTHER_" + r;
        }
    }

    private static String safe(String s, int max) {
        if (s == null) return "";
        s = s.replace('\u0000',' ').trim();
        return s.substring(0, Math.min(max, s.length()));
    }
}
