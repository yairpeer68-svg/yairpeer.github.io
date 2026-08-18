package com.ghosteye.intelligence

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class AuthClient(private val context: Context, private val baseUrl: String) {
    private val http = OkHttpClient()
    private val sessions = SessionStore(context)
    private val jsonType = "application/json".toMediaType()

    suspend fun login(email: String, password: String): Boolean = requestAuth("/api/v1/auth/login", email, password)

    private suspend fun requestAuth(path: String, email: String, password: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email).put("password", password).toString().toRequestBody(jsonType)
        val req = Request.Builder().url(baseUrl + path).post(body).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext false
            val j = JSONObject(requireNotNull(r.body).string())
            sessions.save(j.getString("access_token"), j.getString("refresh_token"))
            true
        }
    }

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val token = sessions.refresh() ?: return@withContext false
        val body = JSONObject().put("refresh_token", token).toString().toRequestBody(jsonType)
        val req = Request.Builder().url("$baseUrl/api/v1/auth/refresh").post(body).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                sessions.clear()
                return@withContext false
            }
            val j = JSONObject(requireNotNull(r.body).string())
            sessions.save(j.getString("access_token"), j.getString("refresh_token"))
            true
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val access = sessions.access()
        try {
            if (!access.isNullOrBlank()) {
                val req = Request.Builder().url("$baseUrl/api/v1/auth/logout")
                    .header("Authorization", "Bearer $access")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                http.newCall(req).execute().close()
            }
        } finally {
            sessions.clear()
        }
    }

    fun accessToken() = sessions.access()
    fun clearLocalSession() = sessions.clear()
}
