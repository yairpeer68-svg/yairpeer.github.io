package com.magen.family.security;

import android.util.Base64;
import android.util.Log;

import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PasswordHasher — PBKDF2-HMAC-SHA256 עם salt לכל מכשיר.
 *
 * למה לא רק SHA-256?
 *   PIN בן 4 ספרות = 10,000 אפשרויות. גם עם SHA-256, מי שיש לו גישה ל-prefs
 *   יכול לחשב את כל ההאשים בשנייה.  PBKDF2 עם 100k iterations הופך את זה
 *   ל~30 שניות לכל ניחוש, ועם salt — לא ניתן להכין rainbow table.
 *
 * פורמט שמירה:  "<base64salt>:<base64hash>"
 */
public class PasswordHasher {

    private static final String TAG = "PasswordHasher";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS  = 100_000;
    private static final int KEY_LENGTH  = 256;  // bits
    private static final int SALT_LENGTH = 16;   // bytes
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * צור hash חדש עם salt רנדומלי.  השתמש בזה כשמגדירים PIN חדש.
     */
    public static String hash(String password) {
        if (password == null) password = "";
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        byte[] hash = pbkdf2(password.toCharArray(), salt);
        return encode(salt) + ":" + encode(hash);
    }

    /**
     * אמת PIN מול hash שמור.
     */
    public static boolean verify(String password, String storedHash) {
        if (password == null || storedHash == null || !storedHash.contains(":")) {
            return false;
        }
        try {
            String[] parts = storedHash.split(":", 2);
            byte[] salt = decode(parts[0]);
            byte[] expected = decode(parts[1]);
            byte[] actual = pbkdf2(password.toCharArray(), salt);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            Log.e(TAG, "verify error: " + e.getMessage());
            return false;
        }
    }

    /**
     * צור קוד חירום רנדומלי בן 6 ספרות.  ההורה צריך לשמור אותו ב-Setup.
     */
    public static String generateEmergencyPin() {
        int code = 100_000 + RANDOM.nextInt(900_000);
        return String.valueOf(code);
    }

    // ---------------- Helpers ----------------

    private static byte[] pbkdf2(char[] password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, KEY_LENGTH);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2 not available", e);
        }
    }

    private static String encode(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    private static byte[] decode(String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
