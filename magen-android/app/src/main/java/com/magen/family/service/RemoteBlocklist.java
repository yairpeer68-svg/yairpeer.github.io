package com.magen.family.service;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

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
        "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/adult/domains";
    private static final String STEVENBLACK_PORN_URL =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts";

    // אופציונלי — רשימה פרטית משלך (השאירי ריק אם אין)
    private static final String CUSTOM_LIST_URL = "";

    private static final String BLOOM_CACHE = "domain_bloom.bin";
    private static final int TIMEOUT_MS = 30000;

    // צפי ~3 מיליון דומיינים, יעד 1% false-positive
    private static final int EXPECTED_DOMAINS = 3_000_000;
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
        try {
            File f = new File(ctx.getFilesDir(), BLOOM_CACHE);
            if (!f.exists()) { Log.d(TAG, "no bloom cache yet"); return; }
            try (FileInputStream fis = new FileInputStream(f)) {
                filter = DomainBloomFilter.readFrom(fis);
            }
            Log.d(TAG, "Loaded bloom from cache: ~" + filter.getItemCount() + " domains");
        } catch (Exception e) {
            Log.e(TAG, "loadFromCache: " + e.getMessage());
        }
    }

    // ---------------- עדכון (הרצה ברקע בלבד!) ----------------

    /**
     * מוריד את כל המקורות, בונה Bloom filter חדש ושומר ל-cache.
     * חייב לרוץ ב-thread רקע (לא ב-UI thread). מחזיר מספר הדומיינים שנטענו.
     */
    public static int update(Context ctx) {
        DomainBloomFilter bloom = new DomainBloomFilter(EXPECTED_DOMAINS, FALSE_POSITIVE);
        int total = 0;

        total += ingest(UT1_ADULT_URL, bloom, SourceType.PLAIN_DOMAINS);
        total += ingest(STEVENBLACK_PORN_URL, bloom, SourceType.HOSTS_FILE);
        if (!CUSTOM_LIST_URL.isEmpty()) {
            total += ingest(CUSTOM_LIST_URL, bloom, SourceType.PLAIN_DOMAINS);
        }

        if (total == 0) {
            Log.w(TAG, "No domains ingested — keeping existing filter");
            return loadedCount();
        }

        // החלפה אטומית + שמירה ל-cache
        filter = bloom;
        try {
            File f = new File(ctx.getFilesDir(), BLOOM_CACHE);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                bloom.writeTo(fos);
            }
        } catch (Exception e) {
            Log.e(TAG, "save cache failed: " + e.getMessage());
        }

        Log.d(TAG, "✓ Blocklist updated: ~" + total + " domains");
        return total;
    }

    private enum SourceType { PLAIN_DOMAINS, HOSTS_FILE }

    /** מוריד מקור אחד ומזין ל-bloom. מחזיר כמה דומיינים נוספו. */
    private static int ingest(String urlStr, DomainBloomFilter bloom, SourceType type) {
        HttpURLConnection conn = null;
        int count = 0;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "HTTP " + conn.getResponseCode() + " for " + urlStr);
                return 0;
            }

            InputStream raw = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                raw = new GZIPInputStream(raw);
            }

            try (BufferedReader r = new BufferedReader(new InputStreamReader(raw))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String domain = parseLine(line, type);
                    if (domain != null) {
                        bloom.add(domain);
                        count++;
                    }
                }
            }
            Log.d(TAG, "Ingested " + count + " from " + shortHost(urlStr));
        } catch (Exception e) {
            Log.e(TAG, "ingest " + shortHost(urlStr) + " failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return count;
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
