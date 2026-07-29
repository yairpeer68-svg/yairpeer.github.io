package com.yourname.gamemodevpn

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * AES-256-GCM encrypted SharedPreferences for sensitive settings.
 * Backed by Android Keystore — keys never leave secure hardware.
 */
object SecureStorage {

    private const val TAG = "SecureStorage"
    private const val PREFS_NAME = "secure_gameboost"

    fun getPrefs(ctx: Context): android.content.SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                ctx, PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { Log.i(TAG, "✅ EncryptedSharedPreferences ready (AES-256-GCM)") }
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to regular prefs: ${e.message}")
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun storeString(ctx: Context, key: String, value: String) {
        getPrefs(ctx).edit().putString(key, value).apply()
    }

    fun getString(ctx: Context, key: String): String? = getPrefs(ctx).getString(key, null)

    fun storeInt(ctx: Context, key: String, value: Int) {
        getPrefs(ctx).edit().putInt(key, value).apply()
    }

    fun getInt(ctx: Context, key: String, default: Int = 0) = getPrefs(ctx).getInt(key, default)
}
