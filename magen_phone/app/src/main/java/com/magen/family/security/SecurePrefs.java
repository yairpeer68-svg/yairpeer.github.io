package com.magen.family.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Small Android-Keystore backed secret store.
 * Ciphertext stays in the app's normal SharedPreferences while the AES key is
 * non-exportable and lives in AndroidKeyStore.
 */
public final class SecurePrefs {
    private static final String TAG = "SecurePrefs";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "magen_local_secrets_v1";
    private static final String PREFIX = "__secure_v1_";
    private static final int IV_LEN = 12;
    private static final SecureRandom RNG = new SecureRandom();

    private SecurePrefs() {}

    public static String getString(Context ctx, String prefsName, String key, String defValue) {
        SharedPreferences p = ctx.getApplicationContext()
            .getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        String enc = p.getString(PREFIX + key, null);
        if (enc != null) {
            String value = decrypt(enc);
            if (value != null) return value;
            return defValue;
        }

        // One-time migration from the previous plaintext field.
        String legacy = p.getString(key, null);
        if (legacy != null) {
            if (putString(ctx, prefsName, key, legacy)) {
                p.edit().remove(key).apply();
            }
            return legacy;
        }
        return defValue;
    }

    public static boolean putString(Context ctx, String prefsName, String key, String value) {
        try {
            String enc = encrypt(value == null ? "" : value);
            ctx.getApplicationContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit().putString(PREFIX + key, enc).remove(key).apply();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "encrypt failed", e);
            return false;
        }
    }

    public static void remove(Context ctx, String prefsName, String key) {
        ctx.getApplicationContext().getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().remove(PREFIX + key).remove(key).apply();
    }

    private static String encrypt(String value) throws Exception {
        SecretKey key = getOrCreateKey();
        byte[] iv = new byte[IV_LEN];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + "."
            + Base64.encodeToString(ct, Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) {
        try {
            String[] parts = encoded.split("\\.", 2);
            if (parts.length != 2) return null;
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] ct = Base64.decode(parts[1], Base64.NO_WRAP);
            if (iv.length != IV_LEN || ct.length < 16) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "decrypt failed", e);
            return null;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        java.security.Key existing = ks.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build();
        kg.init(spec);
        return kg.generateKey();
    }
}
