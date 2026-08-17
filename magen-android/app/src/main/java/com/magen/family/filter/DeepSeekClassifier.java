package com.magen.family.filter;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DeepSeekClassifier — שכבת סיווג טקסט בהקשר, להורדת חסימות שווא.
 *
 * הבעיה שזה פותר:
 *   רשימת מילים עיוורת חוסמת גם "document" (מכיל cum) וגם "analysis" (anal).
 *   גבולות מילה עוזרים, אבל עדיין נחסמים ביטויים לגיטימיים ("sexual health",
 *   "breast cancer awareness"). מודל שפה מבין הקשר — ולכן יכול להבחין בין
 *   תוכן פורנוגרפי לבין תוכן חינוכי/רפואי שמשתמש באותן מילים.
 *
 * למה זה כמו טלגרם ולא "שרת":
 *   קריאת HTTPS ישירה ל-api.deepseek.com עם המפתח של המשתמש. אין תשתית
 *   להקים. המשתמש מזין את המפתח שלו.
 *
 * שלוש הגנות חובה (כי שולחים טקסט מהמסך לצד ג'):
 *   1. פרטיות — כבוי כברירת מחדל. opt-in מפורש. שולחים רק קטע קצר וגבולי,
 *      לא את כל המסך.
 *   2. עלות — המפתח בתוך ה-APK; מי שיחלץ אותו יכול לבזבז. לכן cache אגרסיבי
 *      + throttle + רק על טקסט שכבר עבר סינון מקומי (מקרים גבוליים בלבד).
 *   3. זמינות — כל כשל/timeout נופל בשקט חזרה להחלטה המקומית.
 *
 * המסווג הוא שכבת *אישור* (confirmation), לא שכבת חסימה עצמאית: הוא נשאל רק
 * כשהמסנן המקומי כבר חשד, וכל תפקידו לומר "כן זה באמת בעייתי" או "לא, שחרר".
 */
public final class DeepSeekClassifier {

    private static final String TAG = "DeepSeek";
    private static final String PREFS = "magen_deepseek";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_KEY = "api_key";

    private static final String ENDPOINT = "https://api.deepseek.com/chat/completions";
    private static final String MODEL = "deepseek-chat";
    private static final int TIMEOUT_MS = 12000;
    private static final int MAX_TEXT = 600;              // לא שולחים יותר מזה

    // cache: טקסט מנורמל -> חסום?  מונע קריאות חוזרות על אותו תוכן.
    private static final int CACHE_SIZE = 256;
    private static final Map<String, Boolean> CACHE =
        new LinkedHashMap<String, Boolean>(CACHE_SIZE, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                return size() > CACHE_SIZE;
            }
        };

    // throttle גלובלי — לכל היותר קריאה אחת ל-N מילישניות
    private static final long MIN_INTERVAL_MS = 1500;
    private static volatile long lastCall = 0;

    private DeepSeekClassifier() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p.getBoolean(KEY_ENABLED, false) && !p.getString(KEY_KEY, "").isEmpty();
    }

    public static String getKey(Context ctx) { return prefs(ctx).getString(KEY_KEY, ""); }

    public static void save(Context ctx, String key, boolean enabled) {
        prefs(ctx).edit()
            .putString(KEY_KEY, key.trim())
            .putBoolean(KEY_ENABLED, enabled)
            .apply();
    }

    public static void clear(Context ctx) { prefs(ctx).edit().clear().apply(); }

    /**
     * שאלה סינכרונית — *אסור* לקרוא מה-UI thread או מ-thread של הנגישות.
     * מיועד לקריאה מ-thread רקע ייעודי. מחזיר:
     *   TRUE  — המודל מאשר שזה תוכן בעייתי
     *   FALSE — המודל אומר שזה תמים (שחרר)
     *   null  — לא זמין / שגיאה / כבוי → הקורא נשאר עם ההחלטה המקומית
     */
    public static Boolean classifyBlocking(Context ctx, String text) {
        if (!isEnabled(ctx) || text == null) return null;

        String norm = normalize(text);
        if (norm.isEmpty()) return null;

        synchronized (CACHE) {
            Boolean cached = CACHE.get(norm);
            if (cached != null) return cached;
        }

        long now = System.currentTimeMillis();
        synchronized (DeepSeekClassifier.class) {
            if (now - lastCall < MIN_INTERVAL_MS) return null;   // throttled → מקומי
            lastCall = now;
        }

        Boolean verdict = ask(getKey(ctx), norm);
        if (verdict != null) {
            synchronized (CACHE) { CACHE.put(norm, verdict); }
        }
        return verdict;
    }

    /** ולידציה של המפתח — קריאה קצרה שבודקת שהמפתח והחיבור עובדים. */
    public static boolean validate(String key) {
        if (key == null || key.trim().isEmpty()) return false;
        Boolean r = ask(key.trim(), "hello");
        return r != null;   // כל תשובה תקינה (גם "לא בעייתי") מעידה שהמפתח עובד
    }

    // ---------------- הקריאה ל-DeepSeek ----------------

    private static Boolean ask(String key, String text) {
        HttpURLConnection conn = null;
        try {
            String system = "You are a strict content-safety classifier for a personal "
                + "web filter. Decide if the given text is adult/sexual/pornographic or "
                + "otherwise explicit content that the user is trying to avoid. Medical, "
                + "educational, news, and general text are NOT blocked. Answer with a single "
                + "word: BLOCK or ALLOW.";

            JSONObject sysMsg = new JSONObject().put("role", "system").put("content", system);
            JSONObject usrMsg = new JSONObject().put("role", "user").put("content", text);
            JSONObject body = new JSONObject()
                .put("model", MODEL)
                .put("temperature", 0)
                .put("max_tokens", 3)
                .put("messages", new JSONArray().put(sysMsg).put(usrMsg));

            URL url = new URL(ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "HTTP " + code);
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }

            JSONObject resp = new JSONObject(sb.toString());
            String answer = resp.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim().toUpperCase();
            return answer.contains("BLOCK");
        } catch (Exception e) {
            Log.w(TAG, "ask failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String normalize(String text) {
        String t = text.trim().replaceAll("\\s+", " ");
        if (t.length() > MAX_TEXT) t = t.substring(0, MAX_TEXT);
        return t;
    }
}
