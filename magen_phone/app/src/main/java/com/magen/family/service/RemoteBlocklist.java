package com.magen.family.service;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

import com.magen.family.server.MagenApiClient;
import com.magen.family.server.ServerConfig;
import com.magen.family.server.ServerResponseVerifier;
import org.json.JSONObject;

/**
 * RemoteBlocklist — כיסוי רחב של אתרי תוכן למבוגרים, מתעדכן לבד.
 *
 * במקום רשימה ידנית קבועה בקוד (שמתיישנת ביום שאחרי), מוריד ומאחד את
 * רשימות התוכן-למבוגרים המקצועיות שמתוחזקות עבורך:
 *
 *   1. UT1 (אוניברסיטת טולוז) — קטגוריית "adult", מתעדכנת יומית,
 *      עשרות-מאות דומיינים חדשים בכל יום. רישיון Creative Commons.
 *      זו רשימת הזהב החינמית לתוכן מבוגרים (משמשת מסננים רבים).
 *
 *   2. StevenBlack/hosts — הרחבת porn, כיסוי רחב מאוד. רישיון MIT.
 *
 * שתיהן יחד = מיליוני דומיינים בפועל, נטענים ל-Bloom filter קטן בזיכרון
 * (~כמה MB) עם בדיקה מהירה. הרשימה נשמרת ל-cache כדי לא להוריד בכל הפעלה,
 * ומתעדכנת ברקע (למשל פעם ביום מ-Watchdog).
 *
 * ⚠️ הערות:
 *   • רשת מושבתת בסביבת הבנייה; הקוד מוכן — צריך לבדוק על מכשיר.
 *   • ה-URLs למטה הם מקורות ציבוריים נפוצים. אם מקור משנה מבנה/כתובת,
 *     עדכני כאן. מומלץ לשקול לארח עותק משלך (GitHub Raw) ליציבות.
 *   • אפשר להוסיף רשימה פרטית משלך דרך CUSTOM_LIST_URL.
 */
public class RemoteBlocklist {

    private static final String TAG = "RemoteBlocklist";

    // מקורות ציבוריים מתוחזקים (קטגוריית adult / porn)
    private static final String UT1_ADULT_URL =
        "https://dsi.ut-capitole.fr/blacklists/download/adult.tar.gz";
    private static final String STEVENBLACK_PORN_URL =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn/hosts";

    // אופציונלי — רשימה פרטית משלך (השאירי ריק אם אין)
    private static final String CUSTOM_LIST_URL = "";

    private static final String BLOOM_CACHE = "domain_bloom.bin";
    private static final int TIMEOUT_MS = 30000;
    private static final int MIN_BUILTIN_SOURCE_DOMAINS = 1_000;
    private static final long MAX_SOURCE_CHARS = 128L * 1024 * 1024;
    private static final int MAX_SOURCE_DOMAINS = 8_000_000;

    // ה-VPS כבר מאחד ~4.75M דומיינים. שומרים מרווח צמיחה כדי ששיעור
    // ה-false-positive לא יקפוץ כשהרשימה עוברת את הקיבולת הישנה של 3M.
    private static final int EXPECTED_DOMAINS = 6_000_000;
    private static final double FALSE_POSITIVE = 0.01;

    private static volatile DomainBloomFilter filter = null;

    /** בדיקה מהירה — לשימוש במסננים (נגישות/VPN). */
    public static boolean isBlocked(String host) {
        DomainBloomFilter f = filter;
        return f != null && f.isBlockedHost(host);
    }

    public static boolean isReady() { return filter != null; }

    public static int loadedCount() {
        DomainBloomFilter f = filter;
        return f == null ? 0 : f.getItemCount();
    }

    // ---------------- טעינה מ-cache ----------------

    /** טוען את ה-Bloom filter השמור (קריאה מהירה בהפעלה). */
    public static void loadFromCache(Context ctx) {
        File target = new File(ctx.getFilesDir(), BLOOM_CACHE);
        File backup = new File(ctx.getFilesDir(), BLOOM_CACHE + ".bak");
        try {
            // Recover a previous known-good file if the process died between the two
            // same-directory renames used during publishing.
            if (!target.exists() && backup.exists()) {
                if (!backup.renameTo(target))
                    Log.w(TAG, "could not recover bloom backup");
            }
            if (!target.exists()) { Log.d(TAG, "no bloom cache yet"); return; }
            try (FileInputStream fis = new FileInputStream(target)) {
                filter = DomainBloomFilter.readFrom(fis);
            }
            if (backup.exists() && !backup.delete())
                Log.w(TAG, "could not delete stale bloom backup");
            Log.d(TAG, "Loaded bloom from cache: ~" + filter.getItemCount() + " domains");
        } catch (Exception e) {
            Log.e(TAG, "loadFromCache: " + e.getMessage());
            try {
                if (target.exists() && !target.delete()) Log.w(TAG, "could not delete corrupt bloom cache");
                // If the newly published target is corrupt but a previous cache survived,
                // restore and validate it before giving up.
                if (backup.exists() && backup.renameTo(target)) {
                    try (FileInputStream fis = new FileInputStream(target)) {
                        filter = DomainBloomFilter.readFrom(fis);
                        Log.d(TAG, "Recovered previous bloom cache");
                    }
                }
            } catch (Exception recoveryError) {
                filter = null;
                Log.e(TAG, "bloom recovery failed: " + recoveryError.getMessage());
            }
        }
    }

    // ---------------- עדכון (הרצה ברקע בלבד!) ----------------

    /**
     * מוריד את כל המקורות, בונה Bloom filter חדש ושומר ל-cache.
     * חייב לרוץ ב-thread רקע (לא ב-UI thread). מחזיר מספר הדומיינים שנטענו.
     */
    public static int update(Context ctx) {
        // Prefer the centrally built, application-signed snapshot. It is fetched over
        // the pinned private CA and independently verified using the server signing key.
        // If anything fails we retain the local last-known-good cache and fall back to
        // the original public sources, so the VPS is never a single point of failure.
        if (ServerConfig.ready(ctx)) {
            int serverCount = updateFromMagenServer(ctx);
            if (serverCount > 0) return serverCount;
        }

        DomainBloomFilter bloom = new DomainBloomFilter(EXPECTED_DOMAINS, FALSE_POSITIVE);

        IngestResult ut1 = ingest(UT1_ADULT_URL, bloom, SourceType.UT1_TAR_GZ);
        IngestResult steven = ingest(STEVENBLACK_PORN_URL, bloom, SourceType.HOSTS_FILE);
        IngestResult custom = CUSTOM_LIST_URL.isEmpty()
            ? IngestResult.optionalSkipped()
            : ingest(CUSTOM_LIST_URL, bloom, SourceType.PLAIN_DOMAINS);

        // Never replace a known-good filter with a partial download. The two built-in
        // sources are mandatory; a configured custom source is mandatory as well.
        if (!ut1.success || !steven.success || !custom.success
                || ut1.count < MIN_BUILTIN_SOURCE_DOMAINS
                || steven.count < MIN_BUILTIN_SOURCE_DOMAINS) {
            Log.w(TAG, "Blocklist update incomplete/truncated — keeping last known good cache");
            return -1;
        }

        int total = ut1.count + steven.count + custom.count;
        if (total <= 0) {
            Log.w(TAG, "No domains ingested — keeping existing filter");
            return -1;
        }
        return publishBloom(ctx, bloom, total);
    }

    /** Pull one signed merged blocklist from the paired VPS. */
    private static int updateFromMagenServer(Context ctx) {
        final int MAX_META = 128 * 1024;
        final int MAX_GZIP = 64 * 1024 * 1024;
        final long MAX_DECOMPRESSED_CHARS = 192L * 1024 * 1024;
        try {
            JSONObject meta = MagenApiClient.signedGet(ctx,
                "/v1/blocklist/meta", true);
            String path = meta.optString("path", "");
            String expectedSha = meta.optString("sha256", "").toLowerCase();
            int declaredCount = meta.optInt("domains", 0);
            boolean currentPath = "/v1/blocklist/file".equals(path);
            boolean legacySignedPath = "/downloads/adult-domains.txt.gz".equals(path);
            if ((!currentPath && !legacySignedPath)
                    || !expectedSha.matches("[0-9a-f]{64}")
                    || declaredCount < MIN_BUILTIN_SOURCE_DOMAINS
                    || declaredCount > MAX_SOURCE_DOMAINS) {
                throw new SecurityException("invalid signed blocklist metadata");
            }

            // שרתי v3.0.1 שכבר הותקנו חתמו על נתיב /downloads ישן. מכבדים את
            // ה-meta החתום לצורך SHA/count, אבל את הקובץ עצמו מורידים תמיד
            // דרך endpoint מאומת-מכשיר כדי לא להחזיר download ציבורי.
            String downloadPath = "/v1/blocklist/file";
            byte[] gz = MagenApiClient.signedGetBytes(ctx, downloadPath, MAX_GZIP,
                "application/gzip,application/octet-stream");
            String actualSha = MagenApiClient.sha256Hex(gz);
            if (!expectedSha.equalsIgnoreCase(actualSha))
                throw new SecurityException("blocklist SHA-256 mismatch");

            DomainBloomFilter bloom = new DomainBloomFilter(EXPECTED_DOMAINS, FALSE_POSITIVE);
            int count = 0; long chars = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(new ByteArrayInputStream(gz))))) {
                String line;
                while ((line = r.readLine()) != null) {
                    chars += line.length() + 1L;
                    if (chars > MAX_DECOMPRESSED_CHARS)
                        throw new java.io.IOException("decompressed blocklist too large");
                    if (line.length() > 4096) continue;
                    String d = cleanDomain(line);
                    if (d != null) {
                        bloom.add(d); count++;
                        if (count > MAX_SOURCE_DOMAINS)
                            throw new java.io.IOException("too many domains in server blocklist");
                    }
                }
            }
            // A signed metadata count is also an integrity/format sanity check. Allow a
            // tiny tolerance only for defensive parser normalization, never a truncated list.
            if (count < MIN_BUILTIN_SOURCE_DOMAINS || Math.abs((long)count - declaredCount) > Math.max(10L, declaredCount / 1000L))
                throw new SecurityException("server blocklist count mismatch");
            int result = publishBloom(ctx, bloom, count);
            if (result > 0) Log.d(TAG, "✓ Installed signed VPS blocklist: ~" + count + " domains");
            return result;
        } catch (Exception e) {
            Log.w(TAG, "signed VPS blocklist unavailable: " + e.getMessage());
            return -1;
        }
    }

    /** Atomically persists and publishes a validated Bloom filter. */
    private static int publishBloom(Context ctx, DomainBloomFilter bloom, int total) {
        File target = new File(ctx.getFilesDir(), BLOOM_CACHE);
        File temp = new File(ctx.getFilesDir(), BLOOM_CACHE + ".tmp");
        File backup = new File(ctx.getFilesDir(), BLOOM_CACHE + ".bak");
        boolean oldMoved = false;
        try {
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                bloom.writeTo(fos);
                fos.flush();
                fos.getFD().sync();
            }
            if (backup.exists() && !backup.delete())
                throw new java.io.IOException("could not clear stale bloom backup");
            if (target.exists()) {
                if (!target.renameTo(backup))
                    throw new java.io.IOException("could not preserve old bloom cache");
                oldMoved = true;
            }
            if (!temp.renameTo(target))
                throw new java.io.IOException("could not publish new bloom cache");
            oldMoved = false;
            if (backup.exists() && !backup.delete())
                Log.w(TAG, "could not delete old bloom backup");
            filter = bloom;
        } catch (Exception e) {
            Log.e(TAG, "save cache failed: " + e.getMessage());
            try {
                if (temp.exists()) temp.delete();
                if (oldMoved && backup.exists() && !target.exists() && !backup.renameTo(target))
                    Log.e(TAG, "failed to restore previous bloom cache");
            } catch (Exception ignored) {}
            return -1;
        }
        com.magen.family.filter.DomainVerdict.clearCache();
        Log.d(TAG, "✓ Blocklist updated: ~" + total + " domains");
        return total;
    }

    private enum SourceType { PLAIN_DOMAINS, HOSTS_FILE, UT1_TAR_GZ }

    private static final class IngestResult {
        final boolean success;
        final int count;
        IngestResult(boolean success, int count) { this.success = success; this.count = count; }
        static IngestResult optionalSkipped() { return new IngestResult(true, 0); }
    }

    /** מוריד מקור אחד ומזין ל-bloom. הצלחה נמדדת גם אם המקור הכיל 0 שורות תקינות. */
    private static IngestResult ingest(String urlStr, DomainBloomFilter bloom, SourceType type) {
        HttpURLConnection conn = null;
        int count = 0;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            // adult.tar.gz is already compressed; asking the HTTP layer to gzip it
            // again makes the stream ambiguous on some proxies.
            conn.setRequestProperty("Accept-Encoding",
                type == SourceType.UT1_TAR_GZ ? "identity" : "gzip");
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "HTTP " + conn.getResponseCode() + " for " + urlStr);
                return new IngestResult(false, 0);
            }

            InputStream raw = conn.getInputStream();
            if (type == SourceType.UT1_TAR_GZ) {
                count = ingestUt1TarGz(raw, bloom);
            } else {
                if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                    raw = new GZIPInputStream(raw);
                }
                try (BufferedReader r = new BufferedReader(new InputStreamReader(raw))) {
                    String line;
                    long chars = 0;
                    while ((line = r.readLine()) != null) {
                        chars += line.length() + 1L;
                        if (chars > MAX_SOURCE_CHARS)
                            throw new java.io.IOException("blocklist source too large");
                        if (line.length() > 4096) continue;
                        String domain = parseLine(line, type);
                        if (domain != null) {
                            bloom.add(domain);
                            count++;
                            if (count > MAX_SOURCE_DOMAINS)
                                throw new java.io.IOException("too many domains in source");
                        }
                        if (Thread.currentThread().isInterrupted())
                            throw new java.io.InterruptedIOException("blocklist update interrupted");
                    }
                }
            }
            Log.d(TAG, "Ingested " + count + " from " + shortHost(urlStr));
            return new IngestResult(true, count);
        } catch (Exception e) {
            Log.e(TAG, "ingest " + shortHost(urlStr) + " failed: " + e.getMessage());
            return new IngestResult(false, 0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Minimal streaming TAR reader for UT1 adult.tar.gz. We only need adult/domains,
     * so no third-party archive dependency and no 100MB+ extraction on the phone.
     */
    private static int ingestUt1TarGz(InputStream compressed, DomainBloomFilter bloom) throws Exception {
        GZIPInputStream gz = new GZIPInputStream(compressed, 64 * 1024);
        byte[] header = new byte[512];
        while (readFully(gz, header, 0, 512) == 512) {
            boolean empty = true;
            for (byte b : header) if (b != 0) { empty = false; break; }
            if (empty) break;

            String name = tarString(header, 0, 100);
            long size = tarOctal(header, 124, 12);
            if (size < 0 || size > MAX_SOURCE_CHARS)
                throw new java.io.IOException("invalid UT1 tar entry size");

            int count = 0;
            if (name.endsWith("/domains") || "adult/domains".equals(name)) {
                ByteArrayOutputStream line = new ByteArrayOutputStream(256);
                long remaining = size;
                while (remaining-- > 0) {
                    int c = gz.read();
                    if (c < 0) throw new java.io.EOFException("truncated UT1 tar");
                    if (c == '\n') {
                        if (line.size() <= 4096) {
                            String domain = parseLine(line.toString("UTF-8"), SourceType.PLAIN_DOMAINS);
                            if (domain != null) {
                                bloom.add(domain); count++;
                                if (count > MAX_SOURCE_DOMAINS)
                                    throw new java.io.IOException("too many UT1 domains");
                            }
                        }
                        line.reset();
                    } else if (line.size() <= 4096) {
                        line.write(c);
                    }
                    if (Thread.currentThread().isInterrupted())
                        throw new java.io.InterruptedIOException("blocklist update interrupted");
                }
                if (line.size() > 0 && line.size() <= 4096) {
                    String domain = parseLine(line.toString("UTF-8"), SourceType.PLAIN_DOMAINS);
                    if (domain != null) { bloom.add(domain); count++; }
                }
                skipFully(gz, (512 - (size % 512)) % 512);
                return count;
            }
            skipFully(gz, size + ((512 - (size % 512)) % 512));
        }
        throw new java.io.IOException("adult/domains not found in UT1 archive");
    }

    private static int readFully(InputStream in, byte[] b, int off, int len) throws java.io.IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(b, off + total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static void skipFully(InputStream in, long count) throws java.io.IOException {
        long left = count;
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) {
                if (in.read() < 0) throw new java.io.EOFException("truncated tar padding");
                n = 1;
            }
            left -= n;
        }
    }

    private static String tarString(byte[] h, int off, int len) throws Exception {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return new String(h, off, end - off, "UTF-8").trim();
    }

    private static long tarOctal(byte[] h, int off, int len) {
        long out = 0; boolean any = false;
        for (int i = off; i < off + len; i++) {
            int c = h[i] & 0xff;
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            out = (out << 3) + (c - '0'); any = true;
        }
        return any ? out : 0;
    }

    /** מנתח שורה בודדת לפי סוג המקור. מחזיר דומיין נקי או null. */
    private static String parseLine(String line, SourceType type) {
        if (line == null) return null;
        line = line.trim();
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return null;

        if (type == SourceType.HOSTS_FILE) {
            // פורמט hosts: "0.0.0.0 domain.com" או "127.0.0.1 domain.com"
            String[] parts = line.split("\\s+");
            if (parts.length < 2) return null;
            String host = parts[1].toLowerCase();
            if (host.equals("localhost") || host.equals("0.0.0.0")) return null;
            return cleanDomain(host);
        } else {
            // רשימת דומיינים פשוטה — דומיין לכל שורה
            return cleanDomain(line.toLowerCase());
        }
    }

    private static String cleanDomain(String d) {
        if (d == null) return null;
        d = d.trim().toLowerCase();
        if (d.startsWith("www.")) d = d.substring(4);
        // תיקוף בסיסי — חייב נקודה, בלי רווחים/תווים חריגים
        if (d.isEmpty() || !d.contains(".") || d.contains(" ")) return null;
        return d;
    }

    private static String shortHost(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return url; }
    }
}
