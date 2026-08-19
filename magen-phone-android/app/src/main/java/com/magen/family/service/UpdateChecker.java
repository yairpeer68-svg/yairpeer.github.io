package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import com.magen.family.BuildConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;

import androidx.core.content.FileProvider;

/**
 * Signed static update-manifest checker.
 *
 * Manifest format:
 * {
 *   "payload": "{...JSON...}",
 *   "signature": "base64 ECDSA-SHA256 signature over payload bytes"
 * }
 * payload contains: versionCode, versionName, url, sha256, notes.
 * The APK is downloaded into app-private cache, SHA-256 checked, package name checked,
 * and its signing certificate must match the currently installed release signer before
 * the Package Installer is allowed to see it.
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
    private static final long MAX_APK_BYTES = 300L * 1024L * 1024L;

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
                String apkSha256 = normalizeSha256(o.optString("sha256", ""));
                if (!isAllowedDownloadUrl(manifest, apkUrl))
                    throw new SecurityException("untrusted APK URL");
                if (apkSha256.isEmpty())
                    throw new SecurityException("signed manifest is missing APK sha256");
                File apk = downloadAndVerifyApk(ctx, new URL(apkUrl), remoteCode, apkSha256);
                Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".update-files", apk);
                NotificationHelper.notifyUpdateAvailable(ctx, name, uri);
                Log.d(TAG, "verified signed update available: " + name);
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

    private static String normalizeSha256(String value) {
        String clean = value == null ? "" : value.replace(":", "").trim().toLowerCase(Locale.US);
        return clean.matches("[0-9a-f]{64}") ? clean : "";
    }

    private static File downloadAndVerifyApk(Context ctx, URL apkUrl, int versionCode,
                                             String expectedSha256) throws Exception {
        File dir = new File(ctx.getCacheDir(), "updates");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create update cache");
        File part = new File(dir, "magen-" + versionCode + ".apk.part");
        File out = new File(dir, "magen-" + versionCode + ".apk");
        if (part.exists() && !part.delete()) throw new IOException("cannot clear stale update part");

        HttpURLConnection conn = null;
        long total = 0;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            conn = (HttpURLConnection) apkUrl.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(30_000);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("APK HTTP " + code);
            long declared = conn.getContentLengthLong();
            if (declared > MAX_APK_BYTES) throw new IOException("APK too large");
            try (BufferedInputStream in = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream fos = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_APK_BYTES) throw new IOException("APK exceeded size limit");
                    digest.update(buf, 0, n);
                    fos.write(buf, 0, n);
                }
                fos.flush();
                fos.getFD().sync();
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
        if (total < 1024) { part.delete(); throw new SecurityException("APK unexpectedly small"); }
        String actual = hex(digest.digest());
        if (!constantTimeHexEquals(expectedSha256, actual)) {
            part.delete();
            throw new SecurityException("APK sha256 mismatch");
        }
        verifyApkIdentityAndSigner(ctx, part);
        if (out.exists() && !out.delete()) { part.delete(); throw new IOException("cannot replace cached APK"); }
        if (!part.renameTo(out)) { part.delete(); throw new IOException("cannot publish verified APK"); }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static void verifyApkIdentityAndSigner(Context ctx, File apk) throws Exception {
        String expected = normalizeSha256(BuildConfig.EXPECTED_SIG_SHA256);
        if (expected.isEmpty()) throw new SecurityException("release signer fingerprint unavailable");
        PackageManager pm = ctx.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
            ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (pi == null || !ctx.getPackageName().equals(pi.packageName))
            throw new SecurityException("APK package identity mismatch");
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (pi.signingInfo == null) throw new SecurityException("APK signing info missing");
            signatures = pi.signingInfo.hasMultipleSigners()
                ? pi.signingInfo.getApkContentsSigners()
                : pi.signingInfo.getSigningCertificateHistory();
        } else {
            signatures = pi.signatures;
        }
        if (signatures == null || signatures.length == 0)
            throw new SecurityException("APK has no signer");
        boolean matched = false;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Signature sig : signatures) {
            String fp = hex(md.digest(sig.toByteArray()));
            if (constantTimeHexEquals(expected, fp)) { matched = true; break; }
        }
        if (!matched) throw new SecurityException("APK signer mismatch");
    }

    private static boolean constantTimeHexEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
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
