package com.ghosteye.intelligence

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

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
                            val saved = runCatching { sessions.save(access, refresh) }.getOrDefault(false)
                            return@withContext if (saved) LoginResult.Success else LoginResult.StorageError
                        }
                        response.code == 401 -> return@withContext LoginResult.InvalidCredentials
                        response.code == 429 -> {
                            val retry = response.header("Retry-After")?.toIntOrNull()?.coerceAtLeast(1) ?: 60
                            return@withContext LoginResult.RateLimited(retry)
                        }
                        response.code in setOf(502, 503, 504) && attempt < 2 -> {
                            // Transient upstream/startup errors are retried internally so a
                            // single tap remains a single user action.
                        }
                        else -> return@withContext LoginResult.ServerError(response.code)
                    }
                }
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
        val access = sessions.access() ?: return@withContext false
        when (probeHealth(access)) {
            200 -> true
            401 -> refresh() && sessions.access()?.let { probeHealth(it) == 200 } == true
            else -> false
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

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val token = sessions.refresh() ?: return@withContext false
        val body = JSONObject().put("refresh_token", token).toString().toRequestBody(jsonType)
        val req = Request.Builder().url(baseUrl.trimEnd('/') + "/api/v1/auth/refresh").post(body).build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    if (r.code == 401) sessions.clear()
                    return@withContext false
                }
                val j = JSONObject(r.body?.string().orEmpty())
                val access = j.optString("access_token")
                val refresh = j.optString("refresh_token")
                if (access.isBlank() || refresh.isBlank()) return@withContext false
                sessions.save(access, refresh)
            }
        } catch (_: IOException) {
            false
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
                runCatching { http.newCall(req).execute().close() }
            }
        } finally {
            sessions.clear()
        }
    }

    fun accessToken() = sessions.access()
    fun clearLocalSession() = sessions.clear()
}
