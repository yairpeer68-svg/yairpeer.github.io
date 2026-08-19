package com.magen.family.server;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

/**
 * Automatic VPS reconnect for a device the server already knows.
 *
 * There is deliberately no shared secret in the APK. /v1/recover is a normal
 * signed device request: the VPS accepts it only if this Android Keystore key
 * already matches an enrolled device. A truly new key still requires a one-time
 * enrollment authorization.
 */
public final class AutoServerConnector {
    private static final String TAG = "MagenAutoConnect";
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final ScheduledExecutorService EXEC = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MagenAutoConnect"); t.setDaemon(true); return t;
    });
    private static volatile int attempts;
    private static final String RECOVERY_PREFS = "magen_recovery";
    private static final String RECOVERY_ID = "pending_id";
    private AutoServerConnector() {}

    public static void start(Context c) {
        if (c == null || !STARTED.compareAndSet(false, true)) return;
        final Context app = c.getApplicationContext();
        EXEC.scheduleWithFixedDelay(() -> {
            try {
                if (!ServerConfig.isEnabled(app) || ServerConfig.isEnrolled(app)) return;
                // Do not hammer a public endpoint forever for a genuinely new install.
                if (attempts >= 6) return;
                attempts++;
                if (recoverBlocking(app)) {
                    ServerConfig.setEnrolled(app, true);
                    ServerEventReporter.flushPendingAsync(app);
                    try { PolicySyncManager.syncBlocking(app); } catch (Exception ignored) {}
                    try { HeartbeatManager.sendBlocking(app); } catch (Exception ignored) {}
                    Log.i(TAG, "automatic VPS recovery succeeded");
                }
            } catch (Exception e) {
                Log.w(TAG, "automatic VPS recovery not available: " + e.getClass().getSimpleName());
            }
        }, 1L, 20L, TimeUnit.SECONDS);
    }

    public static boolean recoverBlocking(Context c) throws Exception {
        DeviceIdentity.ensure();
        Context app=c.getApplicationContext();
        android.content.SharedPreferences p=app.getSharedPreferences(RECOVERY_PREFS,Context.MODE_PRIVATE);
        String recoveryId=p.getString(RECOVERY_ID,"");
        if(recoveryId==null || recoveryId.isEmpty()){
            recoveryId=UUID.randomUUID().toString();
            // commit() is intentional: the id must exist before the network request starts so a
            // process death/retry cannot create a second recovery transaction on the VPS.
            if(!p.edit().putString(RECOVERY_ID,recoveryId).commit())
                throw new java.io.IOException("could not persist recovery id");
        }
        JSONObject body = new JSONObject()
            .put("name", Build.MODEL == null ? "Android" : Build.MODEL)
            .put("client_recovery_id",recoveryId);
        JSONObject out = MagenApiClient.signedPost(app, "/v1/recover", body, false);
        boolean ok=out.optBoolean("ok", false);
        if(ok) p.edit().remove(RECOVERY_ID).apply();
        return ok;
    }
}
