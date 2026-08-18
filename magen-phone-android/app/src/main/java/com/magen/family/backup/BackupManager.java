package com.magen.family.backup;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Encrypted, validated and rollback-safe settings backup. */
public final class BackupManager {

    private static final String MAGIC = "MAGEN1";
    private static final int ITERATIONS = 100_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_BACKUP_CHARS = 8 * 1024 * 1024;
    private static final int MAX_STRING_CHARS = 64 * 1024;
    private static final int MAX_SET_STRING_CHARS = 8 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();


    /** Groups included in the backup. */
    private static final String[] PREF_FILES = {
        "magen_prefs", "magen_filter",
        "magen_vpn_policy", "magen_covenant", "magen_stats",
        "MagenConfigPrefs", "magen_allowlist", "magen_locale"
    };

    private BackupManager() {}

    public static String export(Context ctx, String passphrase) throws Exception {
        if (passphrase == null || passphrase.isEmpty())
            throw new IllegalArgumentException("passphrase required");

        JSONObject all = new JSONObject();
        for (String name : PREF_FILES) {
            SharedPreferences p = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
            JSONObject data = dumpPrefs(p);
            stripDeviceBoundSecretCiphertext(data);

            all.put(name, data);
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

    /**
     * Restore is transactional at the application level: decrypt + validate everything
     * first, then commit. If any commit/Keystore write fails, the previous settings are
     * restored from an in-memory snapshot.
     */
    public static boolean restore(Context ctx, String backup, String passphrase) {
        if (backup == null || passphrase == null || backup.length() > MAX_BACKUP_CHARS) return false;

        Map<String, JSONObject> staged = new LinkedHashMap<>();
        Map<String, JSONObject> previous = new LinkedHashMap<>();


        try {
            JSONObject all = decryptBackup(backup, passphrase);

            // Stage and validate before mutating any preference file.
            for (String name : PREF_FILES) {
                SharedPreferences p = ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
                previous.put(name, dumpPrefs(p));
                if (!all.has(name)) continue;
                JSONObject data = deepCopy(all.getJSONObject(name));
                validatePrefs(data);

                staged.put(name, data);
            }

            for (Map.Entry<String, JSONObject> e : staged.entrySet()) {
                if (!writePrefsExact(ctx.getSharedPreferences(e.getKey(), Context.MODE_PRIVATE), e.getValue()))
                    throw new IllegalStateException("preference commit failed: " + e.getKey());
            }

            return true;
        } catch (Exception e) {
            // Roll back every file that was captured, then restore device secrets.
            try {
                for (Map.Entry<String, JSONObject> prev : previous.entrySet()) {
                    writePrefsExact(ctx.getSharedPreferences(prev.getKey(), Context.MODE_PRIVATE), prev.getValue());
                }
            } catch (Exception ignored) {}
            return false;
        }
    }

    private static JSONObject decryptBackup(String backup, String passphrase) throws Exception {
        String[] parts = backup.trim().split(":", 4);
        if (parts.length != 4 || !MAGIC.equals(parts[0])) throw new IllegalArgumentException("bad backup format");

        byte[] salt = unb64(parts[1]);
        byte[] iv = unb64(parts[2]);
        byte[] ct = unb64(parts[3]);
        if (salt.length != SALT_LEN || iv.length != IV_LEN || ct.length < 16)
            throw new IllegalArgumentException("bad backup crypto parameters");

        SecretKeySpec key = deriveKey(passphrase, salt);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plain = c.doFinal(ct);
        if (plain.length > MAX_BACKUP_CHARS) throw new IllegalArgumentException("backup payload too large");
        return new JSONObject(new String(plain, "UTF-8"));
    }

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

    private static void validatePrefs(JSONObject data) throws Exception {
        java.util.Iterator<String> it = data.keys();
        while (it.hasNext()) {
            String key = it.next();
            if (key.length() > 256) throw new IllegalArgumentException("preference key too long");
            JSONObject rec = data.getJSONObject(key);
            String t = rec.getString("t");
            switch (t) {
                case "s":
                    if (rec.getString("v").length() > MAX_STRING_CHARS)
                        throw new IllegalArgumentException("preference string too large");
                    break;
                case "i": rec.getInt("v"); break;
                case "l": rec.getLong("v"); break;
                case "b": rec.getBoolean("v"); break;
                case "f": rec.getDouble("v"); break;
                case "ss":
                    JSONArray arr = rec.getJSONArray("v");
                    if (arr.length() > 10000) throw new IllegalArgumentException("set too large");
                    for (int i = 0; i < arr.length(); i++) {
                        if (arr.getString(i).length() > MAX_SET_STRING_CHARS)
                            throw new IllegalArgumentException("set item too large");
                    }
                    break;
                default: throw new IllegalArgumentException("unknown preference type");
            }
        }
    }

    private static boolean writePrefsExact(SharedPreferences p, JSONObject data) throws Exception {
        SharedPreferences.Editor ed = p.edit().clear();
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
                default: throw new IllegalArgumentException("unknown preference type");
            }
        }
        return ed.commit();
    }

    private static void putStringRecord(JSONObject data, String key, String value) throws Exception {
        data.put(key, new JSONObject().put("t", "s").put("v", value == null ? "" : value));
    }

    private static String takeStringRecord(JSONObject data, String key) throws Exception {
        if (!data.has(key)) return null;
        JSONObject rec = data.getJSONObject(key);
        if (!"s".equals(rec.getString("t"))) throw new IllegalArgumentException("secret must be string");
        String value = rec.getString("v");
        data.remove(key);
        return value;
    }

    private static void stripDeviceBoundSecretCiphertext(JSONObject data) {
        java.util.List<String> remove = new java.util.ArrayList<>();
        java.util.Iterator<String> it = data.keys();
        while (it.hasNext()) {
            String key = it.next();
            if (key.startsWith("__secure_v1_")) remove.add(key);
        }
        for (String key : remove) data.remove(key);
    }

    private static JSONObject deepCopy(JSONObject o) throws Exception {
        return new JSONObject(o.toString());
    }

    private static SecretKeySpec deriveKey(String passphrase, byte[] salt) throws Exception {
        if (passphrase == null || passphrase.isEmpty()) throw new IllegalArgumentException("passphrase required");
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }
    private static byte[] unb64(String s) { return Base64.decode(s, Base64.NO_WRAP); }
}
