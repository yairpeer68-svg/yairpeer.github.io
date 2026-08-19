package com.ghosteye.intelligence

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

class SessionExpiredException : IllegalStateException("session expired")
class ApiException(val code: Int, message: String) : IllegalStateException(message)

class ApiClient(private val context: Context, private val baseUrl: String) {
    private val client = GhostEyeHttp.client
    // Artifact uploads can legitimately take much longer than ordinary JSON API
    // calls on a mobile connection. Keep strict short timeouts for the rest of the
    // app, but give the replay-safe idempotent upload its own bounded client.
    private val uploadClient = client.newBuilder()
        .writeTimeout(15, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .callTimeout(20, TimeUnit.MINUTES)
        .build()
    private val session = SessionStore(context)
    private val auth = AuthClient(context, baseUrl)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun signed(builder: Request.Builder, accessOverride: String? = null): Request {
        val access = accessOverride ?: session.access()
        if (!access.isNullOrBlank()) builder.header("Authorization", "Bearer $access")
        builder.header("Accept", "application/json")
        builder.header("X-Ghost-Eye-Client", BuildConfig.VERSION_NAME)
        builder.header("X-Request-ID", UUID.randomUUID().toString())
        return builder.build()
    }

    private suspend fun call(
        retryIo: Boolean = false,
        httpClient: OkHttpClient = client,
        build: () -> Request.Builder
    ): Response = withContext(Dispatchers.IO) {
        suspend fun execute(access: String?): Response {
            var last: IOException? = null
            repeat(3) { attempt ->
                val request = signed(build(), access)
                try {
                    return httpClient.newCall(request).execute()
                } catch (e: IOException) {
                    last = e
                    if ((!retryIo && request.method != "GET") || attempt == 2) throw e
                    delay(if (attempt == 0) 200 else 600)
                }
            }
            throw last ?: IOException("request failed")
        }

        val tokenUsed = session.access()
        var response = execute(tokenUsed)
        if (response.code == 401) {
            response.close()
            when (auth.refreshIfNeeded(tokenUsed)) {
                RefreshResult.Success -> Unit
                RefreshResult.Invalid -> throw@withContext SessionExpiredException()
                RefreshResult.Unavailable -> throw@withContext ApiException(503, "authentication service temporarily unavailable")
            }
            response = execute(session.access())
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

    private fun parseObject(body: String, operation: String): JSONObject =
        try { JSONObject(body) }
        catch (_: Exception) { throw ApiException(502, "$operation returned invalid JSON") }

    private fun parseArray(body: String, operation: String): JSONArray =
        try { JSONArray(body) }
        catch (_: Exception) { throw ApiException(502, "$operation returned invalid JSON") }

    suspend fun health(): JSONObject =
        call { Request.Builder().url("$baseUrl/health").get() }.use { parseObject(ensureSuccess(it, "Health"), "Health") }

    suspend fun upload(uri: Uri, onProgress: (Int) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = displayName(uri)
        val length = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val main = Handler(Looper.getMainLooper())
        val lastProgress = AtomicInteger(-1)
        val body = object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = length
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var sent = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        sent += read
                        if (length > 0) {
                            val progress = ((sent * 100L) / length).toInt().coerceIn(0, 100)
                            if (progress >= lastProgress.get() + 2 || progress == 100) {
                                lastProgress.set(progress)
                                main.post { onProgress(progress) }
                            }
                        }
                    }
                } ?: error("Unable to open selected file")
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, body).build()
        val idempotencyKey = UUID.randomUUID().toString()

        call(retryIo = true, httpClient = uploadClient) {
            Request.Builder().url("$baseUrl/api/v1/files")
                .header("X-Idempotency-Key", idempotencyKey)
                .post(multipart)
        }.use { r ->
            parseObject(ensureSuccess(r, "Upload"), "Upload").optString("job_id")
                .takeIf { it.isNotBlank() } ?: throw ApiException(502, "Upload response is missing job_id")
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
            parseJob(parseObject(ensureSuccess(r, "Status"), "Status"))
        }

    suspend fun jobs(): List<JobSummary> =
        call { Request.Builder().url("$baseUrl/api/v1/jobs").get() }.use { r ->
            parseJobs(parseArray(ensureSuccess(r, "Jobs"), "Jobs"))
        }

    suspend fun intelligence(jobId: String): IntelligenceSummary =
        call { Request.Builder().url("$baseUrl/api/v2/jobs/$jobId/intelligence").get() }.use { r ->
            val j = parseObject(ensureSuccess(r, "Intelligence"), "Intelligence")
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


    suspend fun mobileBootstrap(): MobileBootstrap {
        val response = call { Request.Builder().url("$baseUrl/api/v2/mobile/bootstrap").get() }
        if (response.code == 404) {
            response.close()
            return legacyBootstrap()
        }
        try {
            val root = parseObject(ensureSuccess(response, "Mobile bootstrap"), "Mobile bootstrap")
            val summary = root.optJSONObject("summary") ?: JSONObject()
            val jobs = parseJobs(root.optJSONArray("jobs") ?: JSONArray())
            val projects = parseProjects(root.optJSONArray("projects") ?: JSONArray())
            val graph = parseGraph(root.optJSONObject("graph") ?: JSONObject())
            val audit = parseAudit(root.optJSONArray("audit") ?: JSONArray())
            val errorsObject = root.optJSONObject("errors") ?: JSONObject()
            val errors = linkedMapOf<String, String>()
            val keys = errorsObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                errors[key] = errorsObject.optString(key, "server component failed")
            }
            return MobileBootstrap(
                version = root.optString("version").takeIf { it.isNotBlank() },
                summary = summary,
                jobs = jobs,
                projects = projects,
                graph = graph,
                audit = audit,
                errors = errors
            )
        } finally {
            response.close()
        }
    }

    private suspend fun legacyBootstrap(): MobileBootstrap {
        val errors = linkedMapOf<String, String>()
        suspend fun <T> safe(name: String, fallback: T, block: suspend () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            errors[name] = e.message ?: e.javaClass.simpleName
            fallback
        }
        val summary = safe("dashboard", JSONObject()) { dashboardSummary() }
        val jobs = safe("jobs", emptyList<JobSummary>()) { jobs() }
        val projects = safe("projects", emptyList<Project>()) { projects() }
        val graph = safe("graph", emptyList<GraphNode>() to emptyList<GraphEdge>()) { graph() }
        val audit = safe("audit", emptyList<AuditItem>()) { audit(50) }
        return MobileBootstrap(null, summary, jobs, projects, graph, audit, errors)
    }

    suspend fun dashboardSummary(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v1/dashboard/summary").get() }.use { r ->
            parseObject(ensureSuccess(r, "Dashboard"), "Dashboard")
        }

    suspend fun projects(): List<Project> =
        call { Request.Builder().url("$baseUrl/api/v1/projects").get() }.use { r ->
            parseProjects(parseArray(ensureSuccess(r, "Projects"), "Projects"))
        }

    suspend fun createProject(name: String): Project =
        call {
            val body = JSONObject().put("name", name.trim()).toString().toRequestBody(jsonType)
            Request.Builder().url("$baseUrl/api/v1/projects").post(body)
        }.use { r ->
            val j = parseObject(ensureSuccess(r, "Create project"), "Create project")
            Project(j.getString("id"), j.getString("name"))
        }

    suspend fun cases(projectId: String): List<CaseItem> =
        call { Request.Builder().url("$baseUrl/api/v1/projects/$projectId/cases").get() }.use { r ->
            val arr = parseArray(ensureSuccess(r, "Cases"), "Cases")
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
            val j = parseObject(ensureSuccess(r, "Create case"), "Create case")
            CaseItem(j.getString("id"), j.optString("title", title.trim()), notes)
        }

    suspend fun graph(): Pair<List<GraphNode>, List<GraphEdge>> =
        call { Request.Builder().url("$baseUrl/api/v1/graph/ui").get() }.use { r ->
            parseGraph(parseObject(ensureSuccess(r, "Graph"), "Graph"))
        }

    suspend fun audit(limit: Int = 30): List<AuditItem> =
        call { Request.Builder().url("$baseUrl/api/v1/audit?limit=$limit").get() }.use { r ->
            parseAudit(parseArray(ensureSuccess(r, "Audit"), "Audit"))
        }

    suspend fun diagnostics(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/system/diagnostics").get() }.use { r ->
            parseObject(ensureSuccess(r, "Diagnostics"), "Diagnostics")
        }

    suspend fun cancelJob(jobId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/platform/jobs/$jobId/cancel").post(ByteArray(0).toRequestBody(null)) }.use { r ->
            parseObject(ensureSuccess(r, "Cancel job"), "Cancel job")
        }

    suspend fun retryJob(jobId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/platform/jobs/$jobId/retry").post(ByteArray(0).toRequestBody(null)) }.use { r ->
            parseObject(ensureSuccess(r, "Retry job"), "Retry job")
        }

    suspend fun signReport(jobId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/operations/jobs/$jobId/reports/sign").post(ByteArray(0).toRequestBody(null)) }.use { r ->
            parseObject(ensureSuccess(r, "Sign report"), "Sign report")
        }

    suspend fun verifyReport(reportId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/operations/reports/$reportId/verify").get() }.use { r ->
            parseObject(ensureSuccess(r, "Verify report"), "Verify report")
        }

    suspend fun compareJobs(oldJobId: String, newJobId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/operations/compare/$oldJobId/$newJobId").get() }.use { r ->
            parseObject(ensureSuccess(r, "Compare jobs"), "Compare jobs")
        }

    private fun parseJobs(arr: JSONArray): List<JobSummary> = buildList {
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val parsed = parseJob(obj)
            if (parsed.id.isNotBlank() && seen.add(parsed.id)) add(parsed)
        }
    }

    private fun parseProjects(arr: JSONArray): List<Project> = buildList {
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { j ->
                val id = j.optString("id").trim()
                if (id.isNotBlank() && seen.add(id)) add(Project(id, j.optString("name", "ללא שם")))
            }
        }
    }

    private fun parseGraph(j: JSONObject): Pair<List<GraphNode>, List<GraphEdge>> {
        val nodesArray = j.optJSONArray("nodes") ?: JSONArray()
        val edgesArray = j.optJSONArray("edges") ?: JSONArray()
        val nodes = buildList {
            for (i in 0 until nodesArray.length()) {
                nodesArray.optJSONObject(i)?.let { n ->
                    val id = n.optString("id")
                    if (id.isNotBlank()) add(GraphNode(id, n.optString("label").takeIf { it.isNotBlank() }, n.optString("kind").takeIf { it.isNotBlank() }))
                }
            }
        }
        val edges = buildList {
            for (i in 0 until edgesArray.length()) {
                edgesArray.optJSONObject(i)?.let { e ->
                    val source = e.optString("source")
                    val target = e.optString("target")
                    if (source.isNotBlank() && target.isNotBlank()) add(GraphEdge(source, target, e.optString("kind").takeIf { it.isNotBlank() }))
                }
            }
        }
        return nodes to edges
    }

    private fun parseAudit(arr: JSONArray): List<AuditItem> = buildList {
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { j ->
                val id = j.optString("id").trim()
                if (id.isBlank() || !seen.add(id)) return@let
                add(AuditItem(
                    id = id,
                    action = j.optString("action", "event"),
                    resourceType = j.optString("resource_type", "system"),
                    resourceId = j.optString("resource_id").takeIf { it.isNotBlank() && it != "null" },
                    createdAt = j.optString("created_at").takeIf { it.isNotBlank() && it != "null" }
                ))
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
