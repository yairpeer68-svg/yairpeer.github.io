package com.yourname.gamemodevpn

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted SharedPreferences using AES256-GCM.
 * Replaces plain ctx.getSharedPreferences("gameboost", ...) calls.
 * Keys and values are encrypted at rest — WireGuard config, server selections, etc.
 * Falls back to plain prefs if encryption setup fails (e.g. on emulators).
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val PREFS_NAME = "gameboost_secure"

    @Volatile private var instance: SharedPreferences? = null

    fun get(ctx: Context): SharedPreferences {
        return instance ?: synchronized(this) {
            instance ?: createPrefs(ctx).also { instance = it }
        }
    }

    private fun createPrefs(ctx: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { Log.i(TAG, "Encrypted prefs initialized") }
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted prefs failed, falling back to plain: ${e.message}")
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    // Convenience helpers
    fun getInt(ctx: Context, key: String, default: Int = 0) = get(ctx).getInt(key, default)
    fun getString(ctx: Context, key: String, default: String? = null) = get(ctx).getString(key, default)
    fun getBoolean(ctx: Context, key: String, default: Boolean = false) = get(ctx).getBoolean(key, default)
    fun getStringSet(ctx: Context, key: String): Set<String> = get(ctx).getStringSet(key, emptySet()) ?: emptySet()

    fun putInt(ctx: Context, key: String, value: Int) = get(ctx).edit().putInt(key, value).apply()
    fun putString(ctx: Context, key: String, value: String) = get(ctx).edit().putString(key, value).apply()
    fun putBoolean(ctx: Context, key: String, value: Boolean) = get(ctx).edit().putBoolean(key, value).apply()
    fun putStringSet(ctx: Context, key: String, value: Set<String>) = get(ctx).edit().putStringSet(key, value).apply()

    fun migrate(ctx: Context) {
        // One-time migration from plain prefs → encrypted prefs
        val plain = ctx.getSharedPreferences("gameboost", Context.MODE_PRIVATE)
        val secure = get(ctx)
        if (secure.getBoolean("_migrated", false)) return
        val editor = secure.edit()
        plain.all.forEach { (k, v) ->
            when (v) {
                is Int     -> editor.putInt(k, v)
                is Boolean -> editor.putBoolean(k, v)
                is String  -> editor.putString(k, v)
                is Float   -> editor.putFloat(k, v)
                is Long    -> editor.putLong(k, v)
                is Set<*>  -> @Suppress("UNCHECKED_CAST") editor.putStringSet(k, v as Set<String>)
            }
        }
        editor.putBoolean("_migrated", true).apply()
        Log.i(TAG, "Migrated ${plain.all.size} preferences to encrypted storage")
    }
}
