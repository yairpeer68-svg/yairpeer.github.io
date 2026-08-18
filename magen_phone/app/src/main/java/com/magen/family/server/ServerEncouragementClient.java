package com.magen.family.server;

import android.content.Context;
import android.util.Log;
import com.magen.family.service.FallSentences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Signed encouragement messages fetched only from the paired Magen VPS. */
public final class ServerEncouragementClient {
    private static final String TAG = "MagenEncouragement";
    private static final long THROTTLE_MS = 15 * 60_000L;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MagenEncouragement"); t.setDaemon(true); return t;
    });
    private static volatile long lastSyncAt = 0;
    private static volatile boolean syncing = false;
    private ServerEncouragementClient() {}

    public static void syncAsync(Context ctx) {
        long now = System.currentTimeMillis();
        if (syncing || now - lastSyncAt < THROTTLE_MS || !ServerConfig.ready(ctx)) return;
        syncing = true;
        Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            try {
                JSONObject payload = MagenApiClient.signedGet(app, "/v1/encouragement", true);

                // v4: retain the server-side context of every sentence.
                JSONArray items = payload.optJSONArray("items");
                if (items != null && items.length() > 0) {
                    FallSentences.replaceStructuredFromServer(app, items);
                    return;
                }

                // Backward compatibility with a v3 server during rolling upgrade.
                JSONArray arr = payload.optJSONArray("sentences");
                if (arr == null) return;
                List<String> list = new ArrayList<>();
                for (int i = 0; i < arr.length() && list.size() < 200; i++) {
                    String line = arr.optString(i, "").trim();
                    if (!line.isEmpty() && line.length() <= 300 && !list.contains(line)) list.add(line);
                }
                if (!list.isEmpty()) FallSentences.replaceFromServer(app, list);
            } catch (Exception e) {
                Log.w(TAG, "sync failed: " + e.getMessage());
            } finally {
                lastSyncAt = System.currentTimeMillis();
                syncing = false;
            }
        });
    }
}
