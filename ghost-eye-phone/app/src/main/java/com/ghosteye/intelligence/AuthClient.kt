package com.ghosteye.intelligence

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class RefreshResult { Success, Invalid, Unavailable }

sealed interface LoginResult {
    data object Success : LoginResult
    data object InvalidCredentials : LoginResult
    data class RateLimited(val retryAfterSeconds: Int) : LoginResult
    data class NetworkError(val message: String) : LoginResult
    data class ServerError(val code: Int) : LoginResult
    data object StorageError : LoginResult
}

class AuthClient(private val context: Context, private val baseUrl: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val sessions = SessionStore(context)
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val refreshMutex = Mutex()

    suspend fun login(email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("email", email.trim().lowercase())
            .put("password", password)
            .toString()
            .toRequestBody(jsonType)
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/auth/login")
            .header("Accept", "application/json")
            .post(body)
            .build()

        var lastNetworkError: IOException? = null
        repeat(3) { attempt ->
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val raw = response.body?.string().orEmpty()
                            val json = runCatching { JSONObject(raw) }.getOrNull()
                                ?: return@withContext LoginResult.ServerError(502)
                            val access = json.optString("access_token")
                            val refresh = json.optString("refresh_token")
                            if (access.isBlank() || refresh.isBlank()) {
                                return@withContext LoginResult.ServerError(502)
                            }
                            val saved = try {
                                sessions.save(access, refresh)
                            } catch (_: Exception) {
                                false
                            }
                            return@withContext if (saved) LoginResult.Success else LoginResult.StorageError
                        }
                        response.code == 401 -> return@withContext LoginResult.InvalidCredentials
                        response.code == 429 -> {
                            val retry = response.header("Retry-After")?.toIntOrNull()?.coerceAtLeast(1) ?: 60
                            return@withContext LoginResult.RateLimited(retry)
                        }
                        response.code in setOf(502, 503, 504) && attempt < 2 -> Unit
                        else -> return@withContext LoginResult.ServerError(response.code)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastNetworkError = e
                if (attempt == 2) {
                    return@withContext LoginResult.NetworkError(e.localizedMessage ?: "network error")
                }
            }
            delay(if (attempt == 0) 250 else 700)
        }
        LoginResult.NetworkError(lastNetworkError?.localizedMessage ?: "network error")
    }

    suspend fun ensureSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            val access = sessions.access() ?: return@withContext false
            when (probeHealth(access)) {
                200 -> true
                401 -> when (refreshIfNeeded(access)) {
                    RefreshResult.Success -> sessions.access()?.let { probeHealth(it) == 200 } == true
                    RefreshResult.Invalid -> false
                    RefreshResult.Unavailable -> false
                }
                else -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Startup must never crash because encrypted state or a response is malformed.
            false
        }
    }

    private fun probeHealth(access: String): Int = try {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/health")
            .header("Authorization", "Bearer $access")
            .get()
            .build()
        http.newCall(req).execute().use { it.code }
    } catch (_: IOException) {
        -1
    }

    /**
     * Refreshes only if the token that failed is still the current token.
     * This is critical when Dashboard starts several requests at once: only one
     * request rotates the refresh token; the others reuse the freshly stored token.
     */
    suspend fun refreshIfNeeded(staleAccessToken: String?): RefreshResult = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val current = sessions.access()
            if (!current.isNullOrBlank() && !staleAccessToken.isNullOrBlank() && current != staleAccessToken) {
                return@withLock RefreshResult.Success
            }
            refreshLocked()
        }
    }

    suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        refreshMutex.withLock { refreshLocked() }
    }

    private fun refreshLocked(): RefreshResult {
        val token = sessions.refresh() ?: return RefreshResult.Invalid
        val body = JSONObject().put("refresh_token", token).toString().toRequestBody(jsonType)
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/auth/refresh")
            .header("Accept", "application/json")
            .post(body)
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    if (r.code == 401) {
                        sessions.clear()
                        return@use RefreshResult.Invalid
                    }
                    return@use RefreshResult.Unavailable
                }
                val j = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
                    ?: return@use RefreshResult.Unavailable
                val access = j.optString("access_token")
                val refresh = j.optString("refresh_token")
                if (access.isBlank() || refresh.isBlank()) return@use RefreshResult.Unavailable
                if (sessions.save(access, refresh)) RefreshResult.Success else RefreshResult.Unavailable
            }
        } catch (_: IOException) {
            RefreshResult.Unavailable
        } catch (_: Exception) {
            RefreshResult.Unavailable
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val access = sessions.access()
        try {
            if (!access.isNullOrBlank()) {
                val req = Request.Builder().url(baseUrl.trimEnd('/') + "/api/v1/auth/logout")
                    .header("Authorization", "Bearer $access")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                try {
                    http.newCall(req).execute().close()
                } catch (_: IOException) {
                    // Local logout must still complete if the network is down.
                }
            }
        } finally {
            sessions.clear()
        }
    }

    fun accessToken() = sessions.access()
    fun clearLocalSession() = sessions.clear()
}
