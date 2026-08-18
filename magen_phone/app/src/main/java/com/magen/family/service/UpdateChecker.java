package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.magen.family.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Signed static update-manifest checker.
 *
 * Manifest format:
 * {
 *   "payload": "{...JSON...}",
 *   "signature": "base64 ECDSA-SHA256 signature over payload bytes"
 * }
 * payload contains: versionCode, versionName, url, notes.
 *
 * The public ECDSA P-256 key is injected at build time with
 * -PupdatePublicKeyBase64=<X509 SubjectPublicKeyInfo base64>.
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String PREFS = "magen_update";
    private static final String K_URL = "manifest_url";
    private static final String K_LAST_CHECK = "last_check";

    private static final long CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final int TIMEOUT_MS = 12000;
    private static final int MAX_MANIFEST_CHARS = 256 * 1024;

    private UpdateChecker() {}

    public static void setManifestUrl(Context ctx, String url) {
        String clean = url == null ? "" : url.trim();
        if (!clean.isEmpty() && !isHttpsUrl(clean)) {
            throw new IllegalArgumentException("Update manifest must use HTTPS");
        }
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(K_URL, clean).apply();
    }

    public static String getManifestUrl(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_URL, "");
    }

    /** Legacy async entry point for UI callers. */
    public static void checkIfDue(Context ctx) {
        new Thread(() -> checkIfDueBlocking(ctx.getApplicationContext()), "UpdateCheck").start();
    }

    /** Blocking entry point for JobService worker threads. */
    public static void checkIfDueBlocking(Context ctx) {
        String url = getManifestUrl(ctx);
        if (url.isEmpty()) return;
        if (!isHttpsUrl(url)) {
            Log.w(TAG, "Ignoring non-HTTPS manifest URL");
            return;
        }
        if (BuildConfig.UPDATE_PUBKEY_B64 == null || BuildConfig.UPDATE_PUBKEY_B64.trim().isEmpty()) {
            Log.w(TAG, "Update checking disabled: no signed-manifest public key configured");
            return;
        }

        SharedPreferences p = ctx.getApplicationContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = p.getLong(K_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;

        try {
            checkNow(ctx, url);
            p.edit().putLong(K_LAST_CHECK, System.currentTimeMillis()).apply();
        } catch (Exception e) {
            // Failed checks are not marked as successful; the scheduler may retry later.
            Log.w(TAG, "check failed: " + e.getMessage());
        }
    }

    private static void checkNow(Context ctx, String manifestUrl) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL manifest = new URL(manifestUrl);
            conn = (HttpURLConnection) manifest.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code != 200) throw new java.io.IOException("manifest HTTP " + code);

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb.length() + line.length() > MAX_MANIFEST_CHARS)
                        throw new java.io.IOException("manifest too large");
                    sb.append(line);
                }
            }

            JSONObject envelope = new JSONObject(sb.toString());
            String payload = envelope.getString("payload");
            String signature = envelope.getString("signature");
            if (!verifyManifest(payload, signature))
                throw new SecurityException("invalid update-manifest signature");

            JSONObject o = new JSONObject(payload);
            int remoteCode = o.optInt("versionCode", 0);
            if (remoteCode <= 0) throw new SecurityException("invalid versionCode");

            @SuppressWarnings("deprecation")
            int localCode = ctx.getPackageManager()
                .getPackageInfo(ctx.getPackageName(), 0).versionCode;

            if (remoteCode > localCode) {
                String name = o.optString("versionName", "").trim();
                String apkUrl = o.optString("url", "").trim();
                if (!isAllowedDownloadUrl(manifest, apkUrl))
                    throw new SecurityException("untrusted APK URL");
                NotificationHelper.notifyUpdateAvailable(ctx, name, apkUrl);
                Log.d(TAG, "signed update available: " + name);
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean verifyManifest(String payload, String sigB64) {
        try {
            byte[] keyBytes = Base64.decode(BuildConfig.UPDATE_PUBKEY_B64, Base64.DEFAULT);
            PublicKey key = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.decode(sigB64, Base64.DEFAULT));
        } catch (Exception e) {
            Log.e(TAG, "signature verification failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean isHttpsUrl(String value) {
        try {
            URL u = new URL(value);
            return "https".equalsIgnoreCase(u.getProtocol()) && u.getHost() != null && !u.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAllowedDownloadUrl(URL manifest, String apkUrl) {
        try {
            URL apk = new URL(apkUrl);
            if (!"https".equalsIgnoreCase(apk.getProtocol())) return false;
            // Keep the update within the same trust boundary as the signed manifest.
            return manifest.getHost().equalsIgnoreCase(apk.getHost());
        } catch (Exception e) {
            return false;
        }
    }
}
