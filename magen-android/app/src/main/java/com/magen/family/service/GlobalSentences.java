package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Html;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GlobalSentences — משפטי חיזוק *משותפים* לכל מי שיש לו את האפליקציה.
 *
 * הרעיון (בקשת המשתמש):
 *   בעל האפליקציה כותב משפטים בערוץ טלגרם ציבורי אחד, וכל האפליקציות
 *   קוראות אותו ומציגות את המשפטים ברגע חסימה. כך "כולם רואים את מה
 *   שאני רושם".
 *
 * למה דרך t.me/s ולא בוט:
 *   קריאה של ערוץ ציבורי דרך עמוד התצוגה https://t.me/s/<channel> לא דורשת
 *   token, לא צורכת updates, ותומכת במספר בלתי-מוגבל של קוראים במקביל —
 *   בדיוק מה שצריך לשידור להמונים, בלי שרת. שם הערוץ ציבורי (לא סוד),
 *   ולכן בטוח להטמיע אותו כברירת מחדל שכל התקנה קוראת אוטומטית.
 */
public final class GlobalSentences {

    private static final String TAG = "GlobalSentences";

    /**
     * ערוץ ברירת המחדל שכל האפליקציות קוראות ממנו.
     * ריק כרגע — יש למלא את שם הערוץ הציבורי (בלי @) כדי שכולם יקראו אותו
     * אוטומטית. המשתמש יכול גם להגדיר ערוץ אחר מתוך האפליקציה.
     */
    public static final String DEFAULT_CHANNEL = "";

    private static final String PREFS = "magen_chizuk";
    private static final String KEY_CHANNEL = "global_channel";

    private static final int TIMEOUT_MS = 15000;
    private static final long THROTTLE_MS = 5 * 60_000L;   // לכל היותר פעם ב-5 דק'

    private static volatile boolean syncing = false;
    private static volatile long lastSyncAt = 0;

    // <div class="tgme_widget_message_text ...">...</div>
    private static final Pattern MSG = Pattern.compile(
        "tgme_widget_message_text[^>]*>(.*?)</div>", Pattern.DOTALL);

    private GlobalSentences() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** שם הערוץ הפעיל (הגדרת המשתמש גוברת על ברירת המחדל). בלי @. */
    public static String getChannel(Context ctx) {
        String c = prefs(ctx).getString(KEY_CHANNEL, DEFAULT_CHANNEL);
        if (c == null) return "";
        return c.trim().replace("@", "").replace("https://t.me/", "").replace("t.me/", "");
    }

    public static void setChannel(Context ctx, String handle) {
        String h = handle == null ? "" : handle.trim()
            .replace("@", "").replace("https://t.me/", "").replace("t.me/", "");
        prefs(ctx).edit().putString(KEY_CHANNEL, h).apply();
    }

    /** סנכרון ברקע, ממותנן. בטוח לקריאה מכל מקום. */
    public static void syncAsync(Context ctx) {
        if (getChannel(ctx).isEmpty()) return;
        long now = System.currentTimeMillis();
        if (syncing || now - lastSyncAt < THROTTLE_MS) return;
        syncing = true;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try { syncBlocking(app); }
            finally { lastSyncAt = System.currentTimeMillis(); syncing = false; }
        }, "GlobalSync").start();
    }

    /** גרסה חוסמת (להרצה כשכבר ב-thread רקע). מחזיר כמות משפטים שנקראו. */
    public static int syncBlocking(Context ctx) {
        String ch = getChannel(ctx);
        if (ch.isEmpty()) return 0;
        try {
            String html = httpGet("https://t.me/s/" + ch);
            if (html == null) return 0;
            List<String> posts = parsePosts(html);
            if (posts.isEmpty()) return 0;
            FallSentences.replaceGlobal(ctx, posts);
            return posts.size();
        } catch (Exception e) {
            Log.w(TAG, "sync: " + e.getMessage());
            return 0;
        }
    }

    private static List<String> parsePosts(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = MSG.matcher(html);
        while (m.find()) {
            String block = m.group(1);
            if (block == null) continue;
            // <br> → שורה חדשה, ואז הסרת שאר תגי HTML ופענוח ישויות
            String text = block.replaceAll("(?i)<br\\s*/?>", "\n");
            text = stripHtml(text).trim();
            if (!text.isEmpty()) out.add(text);
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static String stripHtml(String s) {
        try {
            String plain;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                plain = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
            } else {
                plain = Html.fromHtml(s).toString();
            }
            return plain;
        } catch (Exception e) {
            // גיבוי: הסרה גסה של תגים
            return s.replaceAll("<[^>]+>", "");
        }
    }

    private static String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("GET");
            // UA של דפדפן — עמוד ה-preview מוגש טוב יותר לבקשות "אנושיות"
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "httpGet: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
