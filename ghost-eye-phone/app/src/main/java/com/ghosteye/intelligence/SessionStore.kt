package com.ghosteye.intelligence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(private val context: Context) {
    private val alias = "ghost-eye-session-v10"
    private val legacyAlias = "universal-intelligence-session"
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    init {
        migrateLegacySessionIfNeeded()
    }

    private fun migrateLegacySessionIfNeeded() {
        val version = prefs.getInt("schema", 0)
        if (version >= 10) return
        // Older builds used a different AndroidKeyStore key configuration. Reusing
        // those encrypted blobs can make a valid server login look like a failed
        // login because token persistence throws after the HTTP 200 response.
        prefs.edit().clear().putInt("schema", 10).commit()
        runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(legacyAlias)) ks.deleteEntry(legacyAlias)
        }
    }

    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP
        )
        return "$iv:$data"
    }

    private fun decrypt(value: String): String? {
        return try {
            val parts = value.split(":", limit = 2)
            if (parts.size != 2) null
            else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))
                )
                String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun save(access: String, refresh: String): Boolean {
        val encryptedAccess = encrypt(access)
        val encryptedRefresh = encrypt(refresh)
        // commit() is intentional: navigation must not happen until both tokens
        // are durably stored. This removes a race that was possible with apply().
        return prefs.edit()
            .putInt("schema", 10)
            .putString("access", encryptedAccess)
            .putString("refresh", encryptedRefresh)
            .commit()
    }

    private fun read(name: String): String? {
        val raw = prefs.getString(name, null) ?: return null
        val value = decrypt(raw)
        if (value == null) {
            // Corrupt/legacy state should never trap the UI in a fake logged-in state.
            clear()
        }
        return value
    }

    fun access(): String? = read("access")
    fun refresh(): String? = read("refresh")

    fun clear() {
        prefs.edit().clear().putInt("schema", 10).commit()
    }

    companion object {
        private val KEY_LOCK = Any()
    }
}
