package com.magen.family.backup;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * BackupManager — גיבוי/שחזור מוצפן של כל ההגדרות, בלי שרת.
 *
 * למה זה קיים:
 *   כרגע "נקה נתונים" או החלפת מכשיר מוחקים הכל — כולל קוד הברית, הרצף,
 *   וכל ההגדרות. גיבוי מאפשר לשחזר על מכשיר חדש או אחרי איפוס.
 *
 * אבטחה:
 *   הגיבוי מוצפן ב-AES-256-GCM עם מפתח שנגזר מסיסמה שהמשתמש בוחר (PBKDF2,
 *   100k iterations, salt אקראי). בלי הסיסמה אי אפשר לקרוא את הקובץ —
 *   חשוב, כי הוא מכיל את ה-hash של קוד הברית ואת הטוקנים (טלגרם/DeepSeek).
 *
 * פורמט הקובץ (טקסט, base64):
 *   MAGEN1:<base64(salt)>:<base64(iv)>:<base64(ciphertext)>
 */
public final class BackupManager {

    private static final String MAGIC = "MAGEN1";
    private static final int ITERATIONS = 100_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** קבוצות ההגדרות שנכללות בגיבוי. */
    private static final String[] PREF_FILES = {
        "magen_prefs", "magen_filter", "magen_telegram", "magen_deepseek",
        "magen_vpn_policy", "magen_covenant", "magen_stats",
        "MagenConfigPrefs", "magen_allowlist", "magen_locale"
    };

    private BackupManager() {}

    // ---------------- ייצוא ----------------

    /** בונה מחרוזת גיבוי מוצפנת מכל ההגדרות. */
    public static String export(Context ctx, String passphrase) throws Exception {
        JSONObject all = new JSONObject();
        for (String name : PREF_FILES) {
            SharedPreferences p = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
            all.put(name, dumpPrefs(p));
        }
        byte[] plain = all.toString().getBytes("UTF-8");

        byte[] salt = new byte[SALT_LEN];
        RANDOM.nextBytes(salt);
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);

        SecretKeySpec key = deriveKey(passphrase, salt);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ct = c.doFinal(plain);

        return MAGIC + ":" + b64(salt) + ":" + b64(iv) + ":" + b64(ct);
    }

    // ---------------- ייבוא ----------------

    /** משחזר הגדרות ממחרוזת גיבוי. מחזיר true אם הצליח. */
    public static boolean restore(Context ctx, String backup, String passphrase) {
        try {
            String[] parts = backup.trim().split(":");
            if (parts.length != 4 || !MAGIC.equals(parts[0])) return false;

            byte[] salt = unb64(parts[1]);
            byte[] iv   = unb64(parts[2]);
            byte[] ct   = unb64(parts[3]);

            SecretKeySpec key = deriveKey(passphrase, salt);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = c.doFinal(ct);   // ייכשל אם הסיסמה שגויה (אימות GCM)

            JSONObject all = new JSONObject(new String(plain, "UTF-8"));
            for (String name : PREF_FILES) {
                if (!all.has(name)) continue;
                loadPrefs(ctx.getSharedPreferences(name, Context.MODE_PRIVATE),
                    all.getJSONObject(name));
            }
            return true;
        } catch (Exception e) {
            return false;   // סיסמה שגויה / קובץ פגום
        }
    }

    // ---------------- סריאליזציה של prefs ----------------

    @SuppressWarnings("unchecked")
    private static JSONObject dumpPrefs(SharedPreferences p) throws Exception {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject rec = new JSONObject();
            if (v instanceof String)  { rec.put("t", "s").put("v", v); }
            else if (v instanceof Integer) { rec.put("t", "i").put("v", v); }
            else if (v instanceof Long)    { rec.put("t", "l").put("v", v); }
            else if (v instanceof Boolean) { rec.put("t", "b").put("v", v); }
            else if (v instanceof Float)   { rec.put("t", "f").put("v", (double)(Float) v); }
            else if (v instanceof Set) {
                JSONArray arr = new JSONArray();
                for (String s : (Set<String>) v) arr.put(s);
                rec.put("t", "ss").put("v", arr);
            } else continue;
            o.put(e.getKey(), rec);
        }
        return o;
    }

    private static void loadPrefs(SharedPreferences p, JSONObject data) throws Exception {
        SharedPreferences.Editor ed = p.edit();
        java.util.Iterator<String> it = data.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONObject rec = data.getJSONObject(key);
            String t = rec.getString("t");
            switch (t) {
                case "s":  ed.putString(key, rec.getString("v")); break;
                case "i":  ed.putInt(key, rec.getInt("v")); break;
                case "l":  ed.putLong(key, rec.getLong("v")); break;
                case "b":  ed.putBoolean(key, rec.getBoolean("v")); break;
                case "f":  ed.putFloat(key, (float) rec.getDouble("v")); break;
                case "ss":
                    JSONArray arr = rec.getJSONArray("v");
                    Set<String> set = new java.util.HashSet<>();
                    for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
                    ed.putStringSet(key, set);
                    break;
            }
        }
        ed.apply();
    }

    // ---------------- crypto ----------------

    private static SecretKeySpec deriveKey(String passphrase, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS);
        byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static byte[] unb64(String s) { return Base64.decode(s, Base64.NO_WRAP); }
}
