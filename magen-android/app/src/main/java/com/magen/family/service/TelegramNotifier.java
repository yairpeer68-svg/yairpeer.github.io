package com.magen.family.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * TelegramNotifier — שליחת התראות לשותף האחריות דרך בוט טלגרם.
 *
 * למה טלגרם ולא שרת:
 *   המשתמש ביקש במפורש בלי שרת. טלגרם משמש כאן כ"צינור" בלבד — האפליקציה
 *   שולחת בקשת HTTPS ישירה ל-api.telegram.org, בדיוק כמו שהיא כבר עושה מול
 *   רשימות החסימה. אין תשתית להקים ואין מה לתחזק.
 *
 * איך זה עובד:
 *   1. פותחים בוט אצל @BotFather ומקבלים token.
 *   2. שותף האחריות שולח /start לבוט, וכך נוצר chat.
 *   3. משיגים את ה-chat id (getUpdates מחזיר אותו אחרי ה-/start).
 *   האפליקציה שומרת token + chat id ושולחת אליהם הודעות.
 *
 * מגבלת אמון ישרה:
 *   ה-token יושב בתוך ה-APK. מי שיחלץ אותו יכול לשלוח הודעות מזויפות בשם
 *   הבוט. זו אותה רמת "מספיק טוב למי שלא באמת מנסה" של כל מודל ה-Device Admin
 *   — לא לבנות על זה כאילו הוא חסין.
 */
public class TelegramNotifier {

    private static final String TAG = "TelegramNotifier";
    private static final String PREFS = "magen_telegram";
    private static final String KEY_TOKEN = "bot_token";
    private static final String KEY_CHAT  = "chat_id";
    private static final String KEY_ENABLED = "enabled";

    private static final int TIMEOUT_MS = 15000;
    private static final String API = "https://api.telegram.org/bot";

    private TelegramNotifier() {}

    // ---------------- הגדרות ----------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isConfigured(Context ctx) {
        SharedPreferences p = prefs(ctx);
        return p.getBoolean(KEY_ENABLED, false)
            && !p.getString(KEY_TOKEN, "").isEmpty()
            && !p.getString(KEY_CHAT, "").isEmpty();
    }

    public static String getToken(Context ctx) { return prefs(ctx).getString(KEY_TOKEN, ""); }
    public static String getChatId(Context ctx) { return prefs(ctx).getString(KEY_CHAT, ""); }

    /**
     * שומר את ההגדרות רק אחרי אימות מוצלח.
     * זו התשובה לדרישה "אם שמים מפתח לא תקין — שגיאה, ולא שומר".
     */
    public static void save(Context ctx, String token, String chatId, boolean enabled) {
        prefs(ctx).edit()
            .putString(KEY_TOKEN, token.trim())
            .putString(KEY_CHAT, chatId.trim())
            .putBoolean(KEY_ENABLED, enabled)
            .apply();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    // ---------------- ולידציה ----------------

    public static class ValidationResult {
        public final boolean ok;
        public final String message;   // שם הבוט אם הצליח, אחרת סיבת השגיאה
        public final String resolvedChatId;   // chat id שנמצא אוטומטית, אם היה

        ValidationResult(boolean ok, String message, String chatId) {
            this.ok = ok; this.message = message; this.resolvedChatId = chatId;
        }
    }

    /**
     * מאמת token מול Telegram (getMe) ומנסה לגלות chat id אוטומטית (getUpdates).
     * חייב לרוץ ב-thread רקע — יש בו קריאת רשת.
     */
    public static ValidationResult validate(String token, String chatIdHint) {
        if (token == null || token.trim().isEmpty()) {
            return new ValidationResult(false, "לא הוזן מפתח בוט", null);
        }
        token = token.trim();

        // 1. getMe — בודק שה-token תקין ושהבוט קיים
        JSONObject me = call(token, "getMe", null);
        if (me == null || !me.optBoolean("ok", false)) {
            String desc = me != null ? me.optString("description", "") : "";
            return new ValidationResult(false,
                desc.isEmpty() ? "המפתח אינו תקין או שאין חיבור לאינטרנט" : desc, null);
        }
        String botName = me.optJSONObject("result") != null
            ? me.optJSONObject("result").optString("username", "bot") : "bot";

        // 2. אם ניתן chat id — נוודא ששליחה אליו עובדת
        if (chatIdHint != null && !chatIdHint.trim().isEmpty()) {
            boolean sent = sendRaw(token, chatIdHint.trim(),
                "✅ שומר הברית מחובר. מכאן יגיעו התראות האחריות.");
            if (sent) return new ValidationResult(true, "@" + botName, chatIdHint.trim());
            return new ValidationResult(false,
                "המפתח תקין אך השליחה ל-chat id נכשלה. ודא שהתחלת שיחה עם הבוט (/start).", null);
        }

        // 3. אחרת — ננסה לגלות chat id מ-getUpdates (אחרי ש-/start נשלח)
        JSONObject upd = call(token, "getUpdates", null);
        String discovered = extractChatId(upd);
        if (discovered != null) {
            sendRaw(token, discovered, "✅ שומר הברית מחובר. מכאן יגיעו התראות האחריות.");
            return new ValidationResult(true, "@" + botName, discovered);
        }

        return new ValidationResult(false,
            "המפתח תקין (@" + botName + "). כעת שלח /start לבוט מהטלפון של שותף האחריות, ואז נסה שוב.", null);
    }

    private static String extractChatId(JSONObject updates) {
        try {
            if (updates == null || !updates.optBoolean("ok", false)) return null;
            org.json.JSONArray result = updates.optJSONArray("result");
            if (result == null || result.length() == 0) return null;
            // לוקחים את ה-chat של ההודעה האחרונה
            for (int i = result.length() - 1; i >= 0; i--) {
                JSONObject msg = result.getJSONObject(i).optJSONObject("message");
                if (msg == null) msg = result.getJSONObject(i).optJSONObject("edited_message");
                if (msg != null && msg.optJSONObject("chat") != null) {
                    return String.valueOf(msg.getJSONObject("chat").optLong("id"));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "extractChatId: " + e.getMessage());
        }
        return null;
    }

    // ---------------- שליחה ----------------

    /** שליחה בפועל להתראה. בטוח לקריאה מכל מקום — יורד ל-thread רקע לבד. */
    public static void send(Context ctx, String message) {
        if (!isConfigured(ctx)) return;
        final String token = getToken(ctx);
        final String chat = getChatId(ctx);
        new Thread(() -> sendRaw(token, chat, message), "TelegramSend").start();
    }

    private static boolean sendRaw(String token, String chatId, String text) {
        try {
            String body = "chat_id=" + URLEncoder.encode(chatId, "UTF-8")
                        + "&text=" + URLEncoder.encode(text, "UTF-8")
                        + "&disable_web_page_preview=true";
            JSONObject resp = call(token, "sendMessage", body);
            return resp != null && resp.optBoolean("ok", false);
        } catch (Exception e) {
            Log.w(TAG, "sendRaw: " + e.getMessage());
            return false;
        }
    }

    // ---------------- סנכרון משפטי חיזוק ----------------

    /** מסמן שסנכרון כבר רץ, כדי לא להריץ כמה במקביל. */
    private static volatile boolean syncing = false;
    private static volatile long lastSyncAt = 0;
    private static final long SYNC_THROTTLE_MS = 60_000L;   // לכל היותר פעם בדקה

    /**
     * שולף הודעות טקסט מצ'אט האחריות והופך כל אחת ל"משפט חיזוק" שיקפוץ ברגע
     * חסימה. בטוח לקריאה מכל מקום — יורד ל-thread רקע לבד וממותנן.
     *
     * getUpdates עם offset שמור מבטיח שכל הודעה נשאבת פעם אחת בלבד.
     */
    public static void syncSentencesAsync(Context ctx) {
        if (!isConfigured(ctx)) return;
        long now = System.currentTimeMillis();
        if (syncing || now - lastSyncAt < SYNC_THROTTLE_MS) return;
        syncing = true;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try { syncSentencesBlocking(app); }
            finally { lastSyncAt = System.currentTimeMillis(); syncing = false; }
        }, "TgSync").start();
    }

    /** גרסה חוסמת — לשימוש כשכבר נמצאים ב-thread רקע (למשל אחרי ולידציה). */
    public static int syncSentencesBlocking(Context ctx) {
        if (!isConfigured(ctx)) return 0;
        try {
            String token = getToken(ctx);
            String chat = getChatId(ctx);
            long offset = FallSentences.getOffset(ctx);

            String method = "getUpdates?timeout=0&allowed_updates=%5B%22message%22%5D"
                + (offset > 0 ? "&offset=" + offset : "");
            JSONObject upd = call(token, method, null);
            if (upd == null || !upd.optBoolean("ok", false)) return 0;

            org.json.JSONArray result = upd.optJSONArray("result");
            if (result == null || result.length() == 0) return 0;

            java.util.List<String> found = new java.util.ArrayList<>();
            long maxUpdateId = offset - 1;
            for (int i = 0; i < result.length(); i++) {
                JSONObject u = result.getJSONObject(i);
                long uid = u.optLong("update_id", -1);
                if (uid > maxUpdateId) maxUpdateId = uid;

                JSONObject msg = u.optJSONObject("message");
                if (msg == null) msg = u.optJSONObject("edited_message");
                if (msg == null) continue;

                JSONObject c = msg.optJSONObject("chat");
                if (c == null) continue;
                // רק הצ'אט המוגדר — לא הודעות מצ'אטים אחרים
                if (!chat.equals(String.valueOf(c.optLong("id")))) continue;

                String text = msg.optString("text", "").trim();
                if (text.isEmpty()) continue;
                // מתעלמים מפקודות בוט (/start וכו')
                if (text.startsWith("/")) continue;
                found.add(text);
            }

            if (maxUpdateId >= offset) FallSentences.setOffset(ctx, maxUpdateId + 1);
            if (!found.isEmpty()) {
                FallSentences.addAll(ctx, found);
                return found.size();
            }
            return 0;
        } catch (Exception e) {
            Log.w(TAG, "syncSentences: " + e.getMessage());
            return 0;
        }
    }

    // ---------------- HTTP ----------------

    private static JSONObject call(String token, String method, String postBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API + token + "/" + method);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            if (postBody != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postBody.getBytes("UTF-8"));
                }
            } else {
                conn.setRequestMethod("GET");
            }

            int code = conn.getResponseCode();
            java.io.InputStream in = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, method + " failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
