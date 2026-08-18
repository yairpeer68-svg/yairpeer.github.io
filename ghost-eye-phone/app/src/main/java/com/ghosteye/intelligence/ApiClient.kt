package com.ghosteye.intelligence

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SessionExpiredException : IllegalStateException("session expired")
class ApiException(val code: Int, message: String) : IllegalStateException(message)

class ApiClient(private val context: Context, private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val session = SessionStore(context)
    private val auth = AuthClient(context, baseUrl)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun signed(builder: Request.Builder): Request {
        val access = session.access()
        if (!access.isNullOrBlank()) builder.header("Authorization", "Bearer $access")
        builder.header("Accept", "application/json")
        return builder.build()
    }

    private suspend fun call(build: () -> Request.Builder): Response = withContext(Dispatchers.IO) {
        var response = client.newCall(signed(build())).execute()
        if (response.code == 401) {
            response.close()
            if (!auth.refresh()) throw@withContext SessionExpiredException()
            response = client.newCall(signed(build())).execute()
            if (response.code == 401) {
                response.close()
                auth.clearLocalSession()
                throw@withContext SessionExpiredException()
            }
        }
        response
    }

    private fun ensureSuccess(response: Response, operation: String): String {
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()
            throw ApiException(response.code, if (detail.isNotBlank()) detail else "$operation failed (${response.code})")
        }
        return body
    }

    suspend fun health(): JSONObject =
        call { Request.Builder().url("$baseUrl/health").get() }.use { JSONObject(ensureSuccess(it, "Health")) }

    suspend fun upload(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(uri)
        val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val body = object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = length
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { input -> sink.writeAll(input.source()) }
                    ?: error("Unable to open selected file")
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, body).build()

        call { Request.Builder().url("$baseUrl/api/v1/files").post(multipart) }.use { r ->
            JSONObject(ensureSuccess(r, "Upload")).getString("job_id")
        }
    }

    fun displayName(uri: Uri): String {
        val resolver = context.contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        } ?: uri.lastPathSegment ?: "upload.bin"
    }

    suspend fun status(jobId: String): JobSummary =
        call { Request.Builder().url("$baseUrl/api/v1/jobs/$jobId").get() }.use { r ->
            parseJob(JSONObject(ensureSuccess(r, "Status")))
        }

    suspend fun jobs(): List<JobSummary> =
        call { Request.Builder().url("$baseUrl/api/v1/jobs").get() }.use { r ->
            val arr = JSONArray(ensureSuccess(r, "Jobs"))
            buildList {
                for (i in 0 until arr.length()) add(parseJob(arr.getJSONObject(i)))
            }
        }

    suspend fun intelligence(jobId: String): IntelligenceSummary =
        call { Request.Builder().url("$baseUrl/api/v2/jobs/$jobId/intelligence").get() }.use { r ->
            val j = JSONObject(ensureSuccess(r, "Intelligence"))
            val artifact = j.optJSONObject("artifact") ?: JSONObject()
            val risk = j.optJSONObject("risk") ?: JSONObject()
            val quality = j.optJSONObject("quality") ?: JSONObject()
            val ai = j.optJSONObject("ai") ?: JSONObject()
            val findingsArray = j.optJSONArray("findings") ?: JSONArray()
            val findings = buildList {
                for (i in 0 until findingsArray.length()) {
                    findingsArray.optJSONObject(i)?.let(::add)
                }
            }
            IntelligenceSummary(
                jobId = jobId,
                filename = artifact.optString("filename", "קובץ"),
                fileType = artifact.optString("file_type").takeIf { it.isNotBlank() },
                sha256 = artifact.optString("sha256").takeIf { it.isNotBlank() },
                riskScore = risk.optInt("overall_score", 0),
                findingCount = risk.optInt("finding_count", findings.size),
                evidenceCount = risk.optInt("evidence_count", 0),
                critical = risk.optInt("critical", 0),
                high = risk.optInt("high", 0),
                medium = risk.optInt("medium", 0),
                low = risk.optInt("low", 0),
                qualityScore = when {
                    quality.has("score") -> quality.optInt("score")
                    quality.has("quality_score") -> quality.optInt("quality_score")
                    else -> null
                },
                aiSummary = extractAiSummary(ai),
                findings = findings
            )
        }

    suspend fun dashboardSummary(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v1/dashboard/summary").get() }.use { r ->
            JSONObject(ensureSuccess(r, "Dashboard"))
        }

    suspend fun projects(): List<Project> =
        call { Request.Builder().url("$baseUrl/api/v1/projects").get() }.use { r ->
            val arr = JSONArray(ensureSuccess(r, "Projects"))
            buildList {
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    add(Project(j.getString("id"), j.optString("name", "ללא שם")))
                }
            }
        }

    suspend fun createProject(name: String): Project =
        call {
            val body = JSONObject().put("name", name.trim()).toString().toRequestBody(jsonType)
            Request.Builder().url("$baseUrl/api/v1/projects").post(body)
        }.use { r ->
            val j = JSONObject(ensureSuccess(r, "Create project"))
            Project(j.getString("id"), j.getString("name"))
        }

    suspend fun cases(projectId: String): List<CaseItem> =
        call { Request.Builder().url("$baseUrl/api/v1/projects/$projectId/cases").get() }.use { r ->
            val arr = JSONArray(ensureSuccess(r, "Cases"))
            buildList {
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    add(CaseItem(j.getString("id"), j.optString("title", "ללא כותרת"), j.optString("notes").takeIf { it.isNotBlank() }))
                }
            }
        }

    suspend fun createCase(projectId: String, title: String, notes: String?): CaseItem =
        call {
            val body = JSONObject()
                .put("project_id", projectId)
                .put("title", title.trim())
                .put("notes", notes)
                .toString().toRequestBody(jsonType)
            Request.Builder().url("$baseUrl/api/v1/projects/cases").post(body)
        }.use { r ->
            val j = JSONObject(ensureSuccess(r, "Create case"))
            CaseItem(j.getString("id"), j.optString("title", title.trim()), notes)
        }

    suspend fun graph(): Pair<List<GraphNode>, List<GraphEdge>> =
        call { Request.Builder().url("$baseUrl/api/v1/graph/ui").get() }.use { r ->
            val j = JSONObject(ensureSuccess(r, "Graph"))
            val nodesArray = j.optJSONArray("nodes") ?: JSONArray()
            val edgesArray = j.optJSONArray("edges") ?: JSONArray()
            val nodes = buildList {
                for (i in 0 until nodesArray.length()) {
                    val n = nodesArray.getJSONObject(i)
                    add(GraphNode(n.optString("id"), n.optString("label").takeIf { it.isNotBlank() }, n.optString("kind").takeIf { it.isNotBlank() }))
                }
            }
            val edges = buildList {
                for (i in 0 until edgesArray.length()) {
                    val e = edgesArray.getJSONObject(i)
                    add(GraphEdge(e.optString("source"), e.optString("target"), e.optString("kind").takeIf { it.isNotBlank() }))
                }
            }
            nodes to edges
        }

    suspend fun audit(limit: Int = 30): List<AuditItem> =
        call { Request.Builder().url("$baseUrl/api/v1/audit?limit=$limit").get() }.use { r ->
            val arr = JSONArray(ensureSuccess(r, "Audit"))
            buildList {
                for (i in 0 until arr.length()) {
                    val j = arr.getJSONObject(i)
                    add(
                        AuditItem(
                            id = j.optString("id"),
                            action = j.optString("action", "event"),
                            resourceType = j.optString("resource_type", "system"),
                            resourceId = j.optString("resource_id").takeIf { it.isNotBlank() },
                            createdAt = j.optString("created_at").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }

    private fun parseJob(j: JSONObject): JobSummary = JobSummary(
        id = j.optString("id"),
        filename = j.optString("filename", "קובץ"),
        fileType = j.optString("file_type").takeIf { it.isNotBlank() && it != "null" },
        status = j.optString("status", "unknown"),
        progress = j.optInt("progress", 0),
        stage = j.optString("stage").takeIf { it.isNotBlank() && it != "null" },
        error = j.optString("error").takeIf { it.isNotBlank() && it != "null" },
        createdAt = j.optString("created_at").takeIf { it.isNotBlank() && it != "null" },
        updatedAt = j.optString("updated_at").takeIf { it.isNotBlank() && it != "null" }
    )

    private fun extractAiSummary(ai: JSONObject): String? {
        val directKeys = listOf("summary", "analysis", "executive_summary", "message")
        for (key in directKeys) {
            val value = ai.optString(key)
            if (value.isNotBlank()) return value
        }
        return null
    }
}
