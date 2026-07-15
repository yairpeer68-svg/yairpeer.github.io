package com.sherlock.app.data

import android.content.Context
import android.net.Uri
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Integrates the FaceCheck.ID face-search API: upload a face, poll the search,
 * and return the sites/profiles where that face appears. Requires the user's
 * own FaceCheck API token (a paid service — we only call their API).
 */
class FaceCheckRepository(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("sherlock_prefs", Context.MODE_PRIVATE)
    }

    fun getToken(): String = prefs.getString(KEY_TOKEN, "") ?: ""
    fun setToken(token: String) = prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
    fun isDemo(): Boolean = prefs.getBoolean(KEY_DEMO, false)
    fun setDemo(on: Boolean) = prefs.edit().putBoolean(KEY_DEMO, on).apply()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Runs a full face search. [onProgress] reports 0..100 while FaceCheck works.
     */
    suspend fun search(
        imageUri: Uri,
        onProgress: (Int) -> Unit
    ): FaceSearchResult = withContext(Dispatchers.IO) {
        val token = getToken()
        val demo = isDemo()
        if (token.isBlank()) {
            return@withContext FaceSearchResult(error = "No FaceCheck API token set. Add it in settings.")
        }
        try {
            val bytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                ?: return@withContext FaceSearchResult(error = "Could not read image.")

            // 1) upload the face
            val uploadBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "images", "face.jpg",
                    bytes.toRequestBody("image/jpeg".toMediaType())
                )
                .build()
            val uploadReq = Request.Builder()
                .url("$BASE/api/upload_pic")
                .header("accept", "application/json")
                .header("Authorization", token)
                .post(uploadBody)
                .build()

            val idSearch = client.newCall(uploadReq).execute().use { r ->
                val body = r.body?.string() ?: return@withContext FaceSearchResult(error = "Upload failed (${r.code}).")
                val json = JsonParser.parseString(body).asJsonObject
                json.get("error")?.takeIf { !it.isJsonNull }?.let {
                    return@withContext FaceSearchResult(error = json.get("message")?.asString ?: "Upload rejected.")
                }
                json.get("id_search")?.asString
                    ?: return@withContext FaceSearchResult(error = "No id_search returned.")
            }

            // 2) poll the search
            repeat(MAX_POLLS) {
                val payload = """{"id_search":"$idSearch","with_progress":true,"status_only":false,"demo":$demo}"""
                val searchReq = Request.Builder()
                    .url("$BASE/api/search")
                    .header("accept", "application/json")
                    .header("Authorization", token)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()

                val json = client.newCall(searchReq).execute().use { r ->
                    val body = r.body?.string() ?: return@withContext FaceSearchResult(error = "Search failed (${r.code}).")
                    JsonParser.parseString(body).asJsonObject
                }
                json.get("error")?.takeIf { !it.isJsonNull }?.let {
                    return@withContext FaceSearchResult(error = json.get("message")?.asString ?: "Search error.")
                }
                json.get("progress")?.takeIf { !it.isJsonNull }?.let {
                    runCatching { onProgress(it.asInt) }
                }
                val output = json.get("output")
                if (output != null && !output.isJsonNull) {
                    val items = output.asJsonObject.getAsJsonArray("items") ?: return@withContext FaceSearchResult()
                    val matches = items.mapNotNull { el ->
                        val o = el.asJsonObject
                        val url = o.get("url")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                        val score = o.get("score")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                        val thumb = o.get("base64")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        FaceMatch(url = url, score = score, thumb = thumb)
                    }.sortedByDescending { it.score }
                    return@withContext FaceSearchResult(matches = matches)
                }
                delay(POLL_DELAY_MS)
            }
            FaceSearchResult(error = "Timed out waiting for FaceCheck.")
        } catch (e: Exception) {
            FaceSearchResult(error = e.message ?: "Face search failed.")
        }
    }

    companion object {
        private const val BASE = "https://facecheck.id"
        private const val KEY_TOKEN = "facecheck_token"
        private const val KEY_DEMO = "facecheck_demo"
        private const val MAX_POLLS = 40
        private const val POLL_DELAY_MS = 2000L
    }
}
