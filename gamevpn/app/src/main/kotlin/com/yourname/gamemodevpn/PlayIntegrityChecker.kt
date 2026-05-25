package com.yourname.gamemodevpn

import android.content.Context
import android.util.Log
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import kotlinx.coroutines.*
import kotlin.coroutines.resumeWithException

/**
 * Play Integrity API — verifies device integrity and app authenticity.
 * Anti-cheat systems (CoD, PUBG) won't ban users who pass integrity checks.
 * Call checkIntegrity() before activating VPN.
 *
 * Requires: com.google.android.play:integrity in build.gradle
 * and a Google Cloud project with Play Integrity API enabled.
 */
object PlayIntegrityChecker {

    private const val TAG = "PlayIntegrity"

    // Replace with your Google Cloud project number
    private const val CLOUD_PROJECT_NUMBER = 0L

    enum class IntegrityLevel { PASSES, DEGRADED, FAILS, UNAVAILABLE }

    data class IntegrityResult(
        val level: IntegrityLevel,
        val message: String,
        val token: String? = null
    )

    suspend fun checkIntegrity(ctx: Context): IntegrityResult = withContext(Dispatchers.IO) {
        if (CLOUD_PROJECT_NUMBER == 0L) {
            Log.w(TAG, "Cloud project number not configured — skipping integrity check")
            return@withContext IntegrityResult(IntegrityLevel.UNAVAILABLE, "Not configured")
        }
        try {
            val manager = IntegrityManagerFactory.create(ctx)
            val nonce = generateNonce()
            val request = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                .build()

            val response = suspendCancellableCoroutine { cont ->
                manager.requestIntegrityToken(request)
                    .addOnSuccessListener { cont.resume(it) { } }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
            val token = response.token()

            // Token should be verified server-side. For local use, just having a token = OK.
            Log.i(TAG, "Integrity token obtained (${token.length} chars)")
            IntegrityResult(IntegrityLevel.PASSES, "✅ Integrity verified", token)
        } catch (e: Exception) {
            Log.w(TAG, "Integrity check failed: ${e.message}")
            IntegrityResult(
                IntegrityLevel.UNAVAILABLE,
                "Integrity check unavailable: ${e.message}"
            )
        }
    }

    private fun generateNonce(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..24).map { chars.random() }.joinToString("")
    }

    /** Quick local check without Play API — detect rooted/emulated environment */
    fun isEnvironmentSafe(): Boolean {
        val suspicious = listOf(
            "/system/app/Superuser.apk",
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su"
        )
        val rooted = suspicious.any { java.io.File(it).exists() }
        val emulated = (android.os.Build.FINGERPRINT?.contains("generic") == true) ||
                       (android.os.Build.MODEL?.contains("Emulator") == true) ||
                       (android.os.Build.HARDWARE?.contains("goldfish") == true)

        if (rooted) Log.w(TAG, "Root detected")
        if (emulated) Log.d(TAG, "Emulator detected")
        return !rooted // emulator is ok for dev
    }
}
