package com.magen.family.server;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Detects long main-thread stalls while the device is interactive. */
public final class MainThreadWatchdog {
    private static final long PROBE_MS = 2000L;
    private static final long STALL_MS = 8000L;
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean PENDING = new AtomicBoolean(false);
    private static final AtomicLong LAST_ACK = new AtomicLong(SystemClock.elapsedRealtime());
    private static volatile boolean reported;
    private static volatile long reportedAt;

    private MainThreadWatchdog() {}

    public static void start(Context context) {
        if (context == null || !STARTED.compareAndSet(false,true)) return;
        final Context app=context.getApplicationContext();
        LAST_ACK.set(SystemClock.elapsedRealtime());
        final Handler main=new Handler(Looper.getMainLooper());
        ScheduledExecutorService ex=Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"MagenMainThreadWatchdog"); t.setDaemon(true); return t;
        });
        ex.scheduleWithFixedDelay(()->{
            long now=SystemClock.elapsedRealtime();
            if (!interactive(app)) {
                LAST_ACK.set(now); PENDING.set(false); reported=false; return;
            }
            if (PENDING.compareAndSet(false,true)) {
                main.post(()->{
                    long ack=SystemClock.elapsedRealtime();
                    long delay=Math.max(0L,ack-LAST_ACK.get());
                    LAST_ACK.set(ack); PENDING.set(false);
                    if (reported) {
                        try {
                            JSONObject d=new JSONObject().put("stall_ms",Math.max(delay,ack-reportedAt));
                            ServerEventReporter.report(app,"MAIN_THREAD_RECOVERED","INFO",d);
                        } catch(Exception ignored) {}
                        reported=false;
                    }
                });
            }
            long lag=now-LAST_ACK.get();
            if (lag>=STALL_MS && !reported) {
                reported=true; reportedAt=now;
                try {
                    JSONObject d=new JSONObject().put("lag_ms",lag).put("threshold_ms",STALL_MS);
                    ServerEventReporter.report(app,"MAIN_THREAD_STALL","HIGH",d);
                } catch(Exception ignored) {}
            }
        },PROBE_MS,PROBE_MS,TimeUnit.MILLISECONDS);
    }

    private static boolean interactive(Context c){
        try { PowerManager p=(PowerManager)c.getSystemService(Context.POWER_SERVICE); return p==null||p.isInteractive(); }
        catch(Exception e){ return true; }
    }
}
