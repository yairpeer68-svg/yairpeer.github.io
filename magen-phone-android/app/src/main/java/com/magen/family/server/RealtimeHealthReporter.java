package com.magen.family.server;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Near-real-time health telemetry while the Magen process is alive.
 * Healthy devices report every 20 seconds. When the VPS/network is unavailable,
 * retries back off to at most 120 seconds to avoid battery drain and thundering herds.
 * A modern NetworkCallback can bring the single pending heartbeat forward when
 * connectivity returns; it never creates parallel recurring heartbeat chains.
 */
public final class RealtimeHealthReporter {
    private static final String TAG = "MagenRealtimeHealth";
    public static final long HEARTBEAT_INTERVAL_SECONDS = 20L;
    private static final long MAX_BACKOFF_SECONDS = 120L;
    private static final long POKE_DEBOUNCE_MS = 1500L;
    private static final long PROCESS_START_ELAPSED = SystemClock.elapsedRealtime();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean NETWORK_CALLBACK_REGISTERED = new AtomicBoolean(false);
    private static final AtomicLong LAST_POKE_ELAPSED = new AtomicLong(0L);
    private static final Object SCHEDULE_LOCK = new Object();
    private static volatile ScheduledExecutorService executor;
    private static volatile ScheduledFuture<?> nextTask;
    private static volatile Context appContext;
    private static volatile ConnectivityManager.NetworkCallback networkCallback;

    private RealtimeHealthReporter() {}

    public static long processUptimeMs() {
        return Math.max(0L, SystemClock.elapsedRealtime() - PROCESS_START_ELAPSED);
    }

    public static void start(Context context) {
        if (context == null || !STARTED.compareAndSet(false, true)) return;
        appContext = context.getApplicationContext();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MagenRealtimeHeartbeat");
            t.setDaemon(true);
            return t;
        });
        registerNetworkCallback(appContext);
        scheduleSooner(3L);
    }

    /** Best-effort early report after a meaningful network/protection transition. */
    public static void poke() {
        ScheduledExecutorService e=executor;
        if(e==null || e.isShutdown() || appContext==null) return;
        long now=SystemClock.elapsedRealtime();
        long previous=LAST_POKE_ELAPSED.get();
        if(previous>0 && now-previous<POKE_DEBOUNCE_MS) return;
        if(!LAST_POKE_ELAPSED.compareAndSet(previous,now)) return;
        scheduleSooner(1L);
    }

    private static void registerNetworkCallback(Context context) {
        if(!NETWORK_CALLBACK_REGISTERED.compareAndSet(false,true)) return;
        try {
            ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm==null){ NETWORK_CALLBACK_REGISTERED.set(false); return; }
            networkCallback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network network){ poke(); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps){
                    if(caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) poke();
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch(RuntimeException e){
            NETWORK_CALLBACK_REGISTERED.set(false);
            Log.w(TAG,"network callback unavailable: "+e.getClass().getSimpleName());
        }
    }

    private static void runOnce() {
        Context app=appContext;
        boolean ok=false;
        try {
            if (app != null && ServerConfig.ready(app)) ok=HeartbeatManager.sendBlocking(app);
        } catch (Throwable t) {
            Log.w(TAG, "heartbeat failed: " + t.getClass().getSimpleName());
        }
        int failures=RuntimeHealthState.serverFailureStreak();
        long delay=HEARTBEAT_INTERVAL_SECONDS;
        if(!ok && failures>0){
            int shift=Math.min(3,Math.max(0,failures-1));
            delay=Math.min(MAX_BACKOFF_SECONDS,HEARTBEAT_INTERVAL_SECONDS*(1L<<shift));
        }
        scheduleSooner(delay);
    }

    /**
     * Keep exactly one future task. A new request may move it earlier, but never
     * adds a second recurring chain. This also makes repeated network callbacks cheap.
     */
    private static void scheduleSooner(long seconds) {
        ScheduledExecutorService e=executor;
        if(e==null || e.isShutdown()) return;
        long wanted=Math.max(1L,seconds);
        synchronized(SCHEDULE_LOCK){
            if(nextTask!=null && !nextTask.isDone() && !nextTask.isCancelled()){
                long existing=Math.max(0L,nextTask.getDelay(TimeUnit.SECONDS));
                if(existing<=wanted) return;
                nextTask.cancel(false);
            }
            nextTask=e.schedule(() -> {
                synchronized(SCHEDULE_LOCK){ nextTask=null; }
                runOnce();
            },wanted,TimeUnit.SECONDS);
        }
    }
}
