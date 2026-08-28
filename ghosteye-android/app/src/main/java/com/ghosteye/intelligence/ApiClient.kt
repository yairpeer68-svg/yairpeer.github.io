package com.ghosteye.intelligence

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
class ApiException(val code: Int, message: String, val requestId: String? = null, val retryAfterSeconds: Long? = null) : IllegalStateException(message)

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

    private fun signed(builder: Request.Builder, requestId: String, accessOverride: String? = null): Request {
        val access = accessOverride ?: session.access()
        if (!access.isNullOrBlank()) builder.header("Authorization", "Bearer $access")
        builder.header("Accept", "application/json")
        builder.header("X-Ghost-Eye-Client", BuildConfig.VERSION_NAME)
        builder.header("X-Request-ID", requestId)
        return builder.build()
    }

    private suspend fun call(
        retryIo: Boolean = false,
        httpClient: OkHttpClient = client,
        build: () -> Request.Builder
    ): Response = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        suspend fun execute(access: String?): Response {
            var lastIo: IOException? = null
            repeat(3) { attempt ->
                val request = signed(build(), requestId, access)
                val replaySafe = retryIo || request.method == "GET" || request.method == "HEAD"
                try {
                    val response = httpClient.newCall(request).execute()
                    val transient = response.code == 429 || response.code == 502 || response.code == 503 || response.code == 504
                    if (!transient || !replaySafe || attempt == 2) return response

                    val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(0, 5)
                    response.close()
                    val backoffMs = retryAfterSeconds?.times(1000L)
                        ?: when (attempt) { 0 -> 250L; else -> 750L }
                    delay(backoffMs)
                } catch (e: IOException) {
                    lastIo = e
                    if (!replaySafe || attempt == 2) throw e
                    delay(if (attempt == 0) 250L else 750L)
                }
            }
            throw lastIo ?: IOException("request failed after bounded retries")
        }

        val tokenUsed = session.access()
        var response = execute(tokenUsed)
        if (response.code == 401) {
            response.close()
            when (auth.refreshIfNeeded(tokenUsed)) {
                RefreshResult.Success -> Unit
                RefreshResult.Invalid -> throw SessionExpiredException()
                RefreshResult.Unavailable -> throw ApiException(503, "authentication service temporarily unavailable")
            }
            response = execute(session.access())
            if (response.code == 401) {
                response.close()
                auth.clearLocalSession()
                throw SessionExpiredException()
            }
        }
        response
    }

    private fun ensureSuccess(response: Response, operation: String): String {
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val parsed = runCatching { JSONObject(body) }.getOrNull()
            val detail = parsed?.optString("detail").orEmpty().takeIf { it.isNotBlank() && it != "null" }
            val requestId = response.header("X-Request-ID")
                ?: parsed?.optString("request_id")?.takeIf { it.isNotBlank() && it != "null" }
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            throw ApiException(
                response.code,
                detail ?: "$operation failed (${response.code})",
                requestId = requestId,
                retryAfterSeconds = retryAfter
            )
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
            val findings = jsonObjects(j.optJSONArray("findings") ?: JSONArray())
            val evidence = jsonObjects(j.optJSONArray("evidence") ?: JSONArray())
            val entities = jsonObjects(j.optJSONArray("entities") ?: JSONArray())
            val relationships = jsonObjects(j.optJSONArray("relationships") ?: JSONArray())
            IntelligenceSummary(
                jobId = jobId,
                filename = artifact.optString("filename", "קובץ"),
                fileType = artifact.optString("file_type").takeIf { it.isNotBlank() },
                sha256 = artifact.optString("sha256").takeIf { it.isNotBlank() && it != "null" },
                riskScore = risk.optInt("overall_score", 0),
                findingCount = risk.optInt("finding_count", findings.size),
                evidenceCount = risk.optInt("evidence_count", evidence.size),
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
                findings = findings,
                evidence = evidence,
                entities = entities,
                relationships = relationships,
                metadata = j.optJSONObject("metadata") ?: JSONObject(),
                modules = j.optJSONObject("modules") ?: JSONObject(),
                executive = j.optJSONObject("executive") ?: JSONObject(),
                deepIntelligence = j.optJSONObject("deep_intelligence") ?: JSONObject(),
                changes = j.optJSONObject("changes") ?: JSONObject(),
                fileIntelligence = j.optJSONObject("file_intelligence") ?: JSONObject(),
                artifactChanges = j.optJSONObject("artifact_changes") ?: JSONObject(),
                sbom = j.optJSONObject("sbom") ?: JSONObject(),
                pipeline = j.optJSONObject("pipeline") ?: JSONObject(),
                integrity = j.optJSONObject("integrity") ?: JSONObject()
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
                    val tagsArray = j.optJSONArray("tags") ?: JSONArray()
                    val tags = buildList { for (t in 0 until tagsArray.length()) tagsArray.optString(t).takeIf { it.isNotBlank() }?.let(::add) }
                    add(CaseItem(j.getString("id"), j.optString("title", "ללא כותרת"), j.optString("notes").takeIf { it.isNotBlank() }, j.optString("status", "open"), j.optString("priority", "normal"), tags, j.optInt("investigation_count", 0)))
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
            CaseItem(j.getString("id"), j.optString("title", title.trim()), notes, j.optString("status", "open"), j.optString("priority", "normal"), emptyList(), j.optInt("investigation_count", 0))
        }

    suspend fun updateCase(caseId: String, status: String? = null, priority: String? = null, tags: List<String>? = null): CaseItem =
        call {
            val payload = JSONObject()
            status?.let { payload.put("status", it) }
            priority?.let { payload.put("priority", it) }
            tags?.let { payload.put("tags", JSONArray(it)) }
            val body = payload.toString().toRequestBody(jsonType)
            Request.Builder().url("$baseUrl/api/v1/projects/cases/${java.net.URLEncoder.encode(caseId, "UTF-8")}").patch(body)
        }.use { r ->
            val j = parseObject(ensureSuccess(r, "Update case"), "Update case")
            val tagArray = j.optJSONArray("tags") ?: JSONArray()
            val parsedTags = buildList { for (i in 0 until tagArray.length()) tagArray.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
            CaseItem(j.getString("id"), j.optString("title", "ללא כותרת"), j.optString("notes").takeIf { it.isNotBlank() }, j.optString("status", "open"), j.optString("priority", "normal"), parsedTags, j.optInt("investigation_count", 0))
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

    suspend fun mobileStatus(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/system/mobile-status").get() }.use { r ->
            parseObject(ensureSuccess(r, "Mobile status"), "Mobile status")
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

    suspend fun targetModules(): List<ScanModule> =
        call { Request.Builder().url("$baseUrl/api/v2/targets/modules").get() }.use { r ->
            val root = parseObject(ensureSuccess(r, "Target modules"), "Target modules")
            val arr = root.optJSONArray("modules") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isNotBlank()) add(ScanModule(id, item.optString("name", id), item.optBoolean("active", false)))
                }
            }
        }

    suspend fun startTargetScan(target: String, modules: Set<String>): String =
        call {
            val body = JSONObject()
                .put("target", target.trim())
                .put("modules", JSONArray(modules.toList()))
                .toString().toRequestBody(jsonType)
            Request.Builder().url("$baseUrl/api/v2/targets/scan").post(body)
        }.use { r ->
            parseObject(ensureSuccess(r, "Target scan"), "Target scan")
                .optString("job_id")
                .takeIf { it.isNotBlank() } ?: throw ApiException(502, "Target scan response is missing job_id")
        }

    suspend fun rescanTarget(jobId: String): String =
        call {
            Request.Builder()
                .url("$baseUrl/api/v2/targets/jobs/$jobId/rescan")
                .post(ByteArray(0).toRequestBody(null))
        }.use { r ->
            parseObject(ensureSuccess(r, "Target rescan"), "Target rescan")
                .optString("job_id")
                .takeIf { it.isNotBlank() } ?: throw ApiException(502, "Target rescan response is missing job_id")
        }

    suspend fun targetWatches(): List<TargetWatchSummary> =
        call { Request.Builder().url("$baseUrl/api/v2/targets/watches").get() }.use { r ->
            val arr = parseArray(ensureSuccess(r, "Target watches"), "Target watches")
            buildList {
                for (i in 0 until arr.length()) {
                    val j = arr.optJSONObject(i) ?: continue
                    add(parseTargetWatch(j))
                }
            }
        }

    suspend fun createTargetWatch(target: String, sourceJobId: String?): TargetWatchSummary =
        call {
            val body = JSONObject()
                .put("target", target.trim())
                .put("interval_minutes", 1440)
                .apply { if (!sourceJobId.isNullOrBlank()) put("source_job_id", sourceJobId) }
                .toString().toRequestBody(jsonType)
            Request.Builder().url("$baseUrl/api/v2/targets/watches").post(body)
        }.use { r ->
            parseTargetWatch(parseObject(ensureSuccess(r, "Create target watch"), "Create target watch"))
        }

    suspend fun deleteTargetWatch(watchId: String) {
        call { Request.Builder().url("$baseUrl/api/v2/targets/watches/$watchId").delete() }.use { r ->
            ensureSuccess(r, "Delete target watch")
        }
    }

    suspend fun investigations(limit: Int = 50): List<InvestigationSummary> =
        call { Request.Builder().url("$baseUrl/api/v2/investigations?limit=${limit.coerceIn(1, 100)}").get() }.use { r ->
            val arr = parseArray(ensureSuccess(r, "Investigations"), "Investigations")
            buildList {
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(parseInvestigation(it)) }
            }
        }

    suspend fun investigation(investigationId: String): InvestigationSummary =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId").get() }.use { r ->
            parseInvestigation(parseObject(ensureSuccess(r, "Investigation"), "Investigation"))
        }

    suspend fun investigationIntelligence(investigationId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/intelligence").get() }.use { r ->
            parseObject(ensureSuccess(r, "Investigation intelligence"), "Investigation intelligence")
        }

    suspend fun investigationAutonomy(investigationId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/autonomy").get() }.use { r ->
            parseObject(ensureSuccess(r, "Investigation autonomy"), "Investigation autonomy")
        }

    suspend fun setInvestigationAutonomy(investigationId: String, mode: String): JSONObject =
        call {
            val body = JSONObject().put("mode", mode.trim().lowercase()).toString().toRequestBody(jsonType)
            Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/autonomy").patch(body)
        }.use { r -> parseObject(ensureSuccess(r, "Set investigation autonomy"), "Set investigation autonomy") }

    suspend fun investigationCorrelations(investigationId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/correlations").get() }.use { r ->
            parseObject(ensureSuccess(r, "Investigation correlations"), "Investigation correlations")
        }

    suspend fun intelligenceSourceCatalog(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/intelligence/sources").get() }.use { r ->
            parseObject(ensureSuccess(r, "Intelligence sources"), "Intelligence sources")
        }

    suspend fun intelligenceSourceHealth(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/intelligence/sources/health").get() }.use { r ->
            parseObject(ensureSuccess(r, "Intelligence source health"), "Intelligence source health")
        }

    suspend fun intelligenceMemory(
        entityType: String,
        entityValue: String,
        excludeInvestigationId: String? = null,
        limit: Int = 20
    ): JSONObject {
        val type = java.net.URLEncoder.encode(entityType, "UTF-8")
        val value = java.net.URLEncoder.encode(entityValue, "UTF-8")
        val exclude = excludeInvestigationId?.takeIf { it.isNotBlank() }
            ?.let { "&exclude_investigation_id=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
        val url = "$baseUrl/api/v2/intelligence/memory?entity_type=$type&entity_value=$value&limit=${limit.coerceIn(1, 100)}$exclude"
        return call { Request.Builder().url(url).get() }.use { r ->
            parseObject(ensureSuccess(r, "Intelligence memory"), "Intelligence memory")
        }
    }

    suspend fun intelligenceSourceLookup(
        entityType: String,
        entityValue: String,
        privacyMode: String = "passive_external",
        maxSources: Int = 8
    ): JSONObject {
        val payload = JSONObject()
            .put("entity_type", entityType)
            .put("entity_value", entityValue)
            .put("privacy_mode", privacyMode)
            .put("max_sources", maxSources.coerceIn(1, 12))
        val body = payload.toString().toRequestBody(jsonType)
        return call(retryIo = true) {
            Request.Builder().url("$baseUrl/api/v2/intelligence/lookup").post(body)
        }.use { r -> parseObject(ensureSuccess(r, "Intelligence source lookup"), "Intelligence source lookup") }
    }

    suspend fun investigationSources(investigationId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/sources").get() }.use { r ->
            parseObject(ensureSuccess(r, "Investigation sources"), "Investigation sources")
        }

    suspend fun investigationSourceTimeline(investigationId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/timeline").get() }.use { r ->
            parseObject(ensureSuccess(r, "Source timeline"), "Source timeline")
        }

    suspend fun refreshInvestigationSources(
        investigationId: String,
        privacyMode: String = "passive_external",
        maxSources: Int = 8
    ): JSONObject {
        val payload = JSONObject()
            .put("privacy_mode", privacyMode)
            .put("max_sources", maxSources.coerceIn(1, 12))
        val body = payload.toString().toRequestBody(jsonType)
        return call(retryIo = true) {
            Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/sources/refresh").post(body)
        }.use { r -> parseObject(ensureSuccess(r, "Refresh intelligence sources"), "Refresh intelligence sources") }
    }

    suspend fun unifiedCapabilities(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v4/capabilities").get() }.use { r ->
            parseObject(ensureSuccess(r, "Unified capabilities"), "Unified capabilities")
        }

    suspend fun providerSla(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v4/providers/sla").get() }.use { r ->
            parseObject(ensureSuccess(r, "Provider SLA"), "Provider SLA")
        }

    suspend fun externalConnectors(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v4/connectors").get() }.use { r ->
            parseObject(ensureSuccess(r, "External connectors"), "External connectors")
        }

    suspend fun sandboxV3Status(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v4/sandbox/status").get() }.use { r ->
            parseObject(ensureSuccess(r, "Sandbox status"), "Sandbox status")
        }

    suspend fun certificationMatrix(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v4/certification/matrix").get() }.use { r ->
            parseObject(ensureSuccess(r, "Certification matrix"), "Certification matrix")
        }

    suspend fun intelligencePlaybooks(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v4/playbooks").get() }.use { r ->
            parseObject(ensureSuccess(r, "Investigation playbooks"), "Investigation playbooks")
        }

    suspend fun intelligenceWatchlistsV14(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v4/watchlists").get() }.use { r ->
            parseObject(ensureSuccess(r, "Intelligence watchlists"), "Intelligence watchlists")
        }

    suspend fun intelligenceAlertsV14(limit: Int = 50): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v4/alerts?limit=${limit.coerceIn(1, 200)}").get() }.use { r ->
            parseObject(ensureSuccess(r, "Intelligence alerts"), "Intelligence alerts")
        }

    suspend fun createEntityWatchlistV14(name: String, entityType: String, entityValue: String): JSONObject =
        call {
            val payload = JSONObject()
                .put("name", name.take(200))
                .put("entities", JSONArray().put(JSONObject().put("type", entityType).put("value", entityValue)))
                .put("rules", JSONObject())
                .put("budget", JSONObject())
            Request.Builder().url("$baseUrl/api/v4/watchlists").post(payload.toString().toRequestBody(jsonType))
        }.use { r -> parseObject(ensureSuccess(r, "Create watchlist"), "Create watchlist") }

    suspend fun infrastructureClusters(maxNodes: Int = 500): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v4/clusters?max_nodes=${maxNodes.coerceIn(10, 3000)}").get() }.use { r ->
            parseObject(ensureSuccess(r, "Infrastructure clusters"), "Infrastructure clusters")
        }

    suspend fun globalIntelligenceSearch(
        query: String,
        entityType: String? = null,
        limit: Int = 50
    ): JSONObject {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val type = entityType?.trim()?.takeIf { it.isNotBlank() }
            ?.let { "&entity_type=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
        val url = "$baseUrl/api/v3/intelligence/search?q=$q&limit=${limit.coerceIn(1, 100)}$type"
        return call { Request.Builder().url(url).get() }.use { r ->
            parseObject(ensureSuccess(r, "Global intelligence search"), "Global intelligence search")
        }
    }

    suspend fun globalEntity(entityId: String): JSONObject {
        val id = java.net.URLEncoder.encode(entityId, "UTF-8")
        return call { Request.Builder().url("$baseUrl/api/v3/intelligence/entities/$id").get() }.use { r ->
            parseObject(ensureSuccess(r, "Global entity"), "Global entity")
        }
    }

    suspend fun globalEntityTimeline(entityId: String, limit: Int = 100): JSONObject {
        val id = java.net.URLEncoder.encode(entityId, "UTF-8")
        return call { Request.Builder().url("$baseUrl/api/v3/intelligence/entities/$id/timeline?limit=${limit.coerceIn(1, 500)}").get() }.use { r ->
            parseObject(ensureSuccess(r, "Global entity timeline"), "Global entity timeline")
        }
    }

    suspend fun globalEntityGraph(entityId: String, depth: Int = 2, maxNodes: Int = 150): JSONObject {
        val id = java.net.URLEncoder.encode(entityId, "UTF-8")
        val url = "$baseUrl/api/v3/intelligence/graph?entity_id=$id&depth=${depth.coerceIn(0, 3)}&max_nodes=${maxNodes.coerceIn(1, 250)}"
        return call { Request.Builder().url(url).get() }.use { r ->
            parseObject(ensureSuccess(r, "Global intelligence graph"), "Global intelligence graph")
        }
    }

    suspend fun syncInvestigationKnowledge(investigationId: String): JSONObject {
        val id = java.net.URLEncoder.encode(investigationId, "UTF-8")
        val body = "{}".toRequestBody(jsonType)
        return call { Request.Builder().url("$baseUrl/api/v3/intelligence/investigations/$id/sync").post(body) }.use { r ->
            parseObject(ensureSuccess(r, "Sync global knowledge"), "Sync global knowledge")
        }
    }

    suspend fun investigationKnowledge(investigationId: String): JSONObject {
        val id = java.net.URLEncoder.encode(investigationId, "UTF-8")
        return call { Request.Builder().url("$baseUrl/api/v3/intelligence/investigations/$id/knowledge").get() }.use { r ->
            parseObject(ensureSuccess(r, "Investigation global knowledge"), "Investigation global knowledge")
        }
    }

    suspend fun investigationCopilotV14(investigationId: String): JSONObject {
        val id = java.net.URLEncoder.encode(investigationId, "UTF-8")
        return call { Request.Builder().url("$baseUrl/api/v4/investigations/$id/copilot").get() }.use { r ->
            parseObject(ensureSuccess(r, "Investigation copilot"), "Investigation copilot")
        }
    }

    suspend fun investigationChallengeV14(investigationId: String): JSONObject {
        val id = java.net.URLEncoder.encode(investigationId, "UTF-8")
        return call { Request.Builder().url("$baseUrl/api/v4/investigations/$id/challenge").get() }.use { r ->
            parseObject(ensureSuccess(r, "Hypothesis challenge"), "Hypothesis challenge")
        }
    }

    suspend fun investigationMultiReviewV14(investigationId: String): JSONObject {
        val id = java.net.URLEncoder.encode(investigationId, "UTF-8")
        return call { Request.Builder().url("$baseUrl/api/v4/investigations/$id/multi-agent-review").get() }.use { r ->
            parseObject(ensureSuccess(r, "Multi-agent review"), "Multi-agent review")
        }
    }

    suspend fun investigationEvidenceChain(investigationId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/evidence-chain").get() }.use { r ->
            parseObject(ensureSuccess(r, "Investigation evidence chain"), "Investigation evidence chain")
        }

    suspend fun investigationExplain(investigationId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/explain").get() }.use { r ->
            parseObject(ensureSuccess(r, "Investigation explanation"), "Investigation explanation")
        }

    suspend fun createInvestigationSnapshot(investigationId: String, label: String? = null): JSONObject =
        call {
            val body = JSONObject().apply {
                label?.trim()?.takeIf { it.isNotBlank() }?.let { put("label", it.take(200)) }
            }.toString().toRequestBody(jsonType)
            Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/snapshots").post(body)
        }.use { r ->
            parseObject(ensureSuccess(r, "Create investigation snapshot"), "Create investigation snapshot")
        }

    suspend fun investigationSnapshots(investigationId: String, limit: Int = 20): List<JSONObject> =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/snapshots?limit=${limit.coerceIn(1, 100)}").get() }.use { r ->
            jsonObjects(parseArray(ensureSuccess(r, "Investigation snapshots"), "Investigation snapshots"))
        }

    suspend fun compareInvestigationSnapshots(oldSnapshotId: String, newSnapshotId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/snapshots/compare/$oldSnapshotId/$newSnapshotId").get() }.use { r ->
            parseObject(ensureSuccess(r, "Compare investigation snapshots"), "Compare investigation snapshots")
        }

    suspend fun deleteInvestigationSnapshot(snapshotId: String) {
        call { Request.Builder().url("$baseUrl/api/v2/investigations/snapshots/$snapshotId").delete() }.use { r ->
            ensureSuccess(r, "Delete investigation snapshot")
        }
    }

    suspend fun createInvestigationFromJob(jobId: String, authorizedNetworkTargets: Boolean): InvestigationSummary =
        call {
            val body = JSONObject()
                .put("seed_job_id", jobId)
                .put("authorized_network_targets", authorizedNetworkTargets)
                .put("max_depth", 2)
                .put("max_nodes", 30)
                .put("max_target_scans", 6)
                .put("max_concurrent_jobs", 2)
                .toString().toRequestBody(jsonType)
            Request.Builder().url("$baseUrl/api/v2/investigations").post(body)
        }.use { r -> parseInvestigation(parseObject(ensureSuccess(r, "Create investigation"), "Create investigation")) }

    suspend fun createInvestigationFromTarget(target: String): InvestigationSummary =
        call {
            val body = JSONObject()
                .put("target", target.trim())
                .put("authorized_network_targets", true)
                .put("max_depth", 2)
                .put("max_nodes", 30)
                .put("max_target_scans", 6)
                .put("max_concurrent_jobs", 2)
                .toString().toRequestBody(jsonType)
            Request.Builder().url("$baseUrl/api/v2/investigations").post(body)
        }.use { r -> parseInvestigation(parseObject(ensureSuccess(r, "Create investigation"), "Create investigation")) }

    suspend fun pauseInvestigation(investigationId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/pause").post(ByteArray(0).toRequestBody(null)) }.use { r ->
            parseObject(ensureSuccess(r, "Pause investigation"), "Pause investigation")
        }

    suspend fun resumeInvestigation(investigationId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/resume").post(ByteArray(0).toRequestBody(null)) }.use { r ->
            parseObject(ensureSuccess(r, "Resume investigation"), "Resume investigation")
        }

    suspend fun cancelInvestigation(investigationId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/cancel").post(ByteArray(0).toRequestBody(null)) }.use { r ->
            parseObject(ensureSuccess(r, "Cancel investigation"), "Cancel investigation")
        }

    suspend fun compareInvestigations(oldId: String, newId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/compare/$oldId/$newId").get() }.use { r ->
            parseObject(ensureSuccess(r, "Compare investigations"), "Compare investigations")
        }

    suspend fun signInvestigationReport(investigationId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId/reports/sign").post(ByteArray(0).toRequestBody(null)) }.use { r ->
            parseObject(ensureSuccess(r, "Sign investigation report"), "Sign investigation report")
        }

    suspend fun verifyInvestigationReport(reportId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v2/investigations/reports/$reportId/verify").get() }.use { r ->
            parseObject(ensureSuccess(r, "Verify investigation report"), "Verify investigation report")
        }

    suspend fun deleteInvestigation(investigationId: String) {
        call { Request.Builder().url("$baseUrl/api/v2/investigations/$investigationId").delete() }.use { r ->
            ensureSuccess(r, "Delete investigation")
        }
    }

    suspend fun deleteJob(jobId: String) {
        call { Request.Builder().url("$baseUrl/api/v2/manage/jobs/$jobId").delete() }.use { r ->
            ensureSuccess(r, "Delete job")
        }
    }

    suspend fun deleteProject(projectId: String) {
        call { Request.Builder().url("$baseUrl/api/v2/manage/projects/$projectId").delete() }.use { r ->
            ensureSuccess(r, "Delete project")
        }
    }

    suspend fun deleteCase(caseId: String) {
        call { Request.Builder().url("$baseUrl/api/v2/manage/cases/$caseId").delete() }.use { r ->
            ensureSuccess(r, "Delete case")
        }
    }

    suspend fun deleteReport(reportId: String) {
        call { Request.Builder().url("$baseUrl/api/v2/manage/reports/$reportId").delete() }.use { r ->
            ensureSuccess(r, "Delete report")
        }
    }

    suspend fun deleteAudit(eventId: String) {
        call { Request.Builder().url("$baseUrl/api/v2/manage/audit/$eventId").delete() }.use { r ->
            ensureSuccess(r, "Delete audit event")
        }
    }

    private fun jsonObjects(arr: JSONArray): List<JSONObject> = buildList {
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::add)
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


    suspend fun cyberCapabilities(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v5/cyber/capabilities").get() }.use { parseObject(ensureSuccess(it, "Cyber capabilities"), "Cyber capabilities") }

    suspend fun cyberControl(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v5/cyber/control").get() }.use { parseObject(ensureSuccess(it, "Cyber control"), "Cyber control") }

    suspend fun providerVaultStatus(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v5/providers").get() }.use { parseObject(ensureSuccess(it, "Provider vault"), "Provider vault") }

    suspend fun saveProviderSecret(provider: String, secret: String, reasoning: Boolean = true): JSONObject {
        val body = JSONObject()
            .put("secret", secret)
            .put("permissions", JSONObject()
                .put("passive_lookup", true)
                .put("reasoning", reasoning)
                .put("read", true)
                .put("sync", true)
                .put("file_submission", false)
                .put("url_submission", false))
            .put("metadata", JSONObject().put("source", "android-v15"))
            .toString().toRequestBody(jsonType)
        return call { Request.Builder().url("$baseUrl/api/v5/providers/${Uri.encode(provider)}").put(body) }
            .use { parseObject(ensureSuccess(it, "Save provider"), "Save provider") }
    }

    suspend fun updateProviderSettings(provider: String, enabled: Boolean? = null, permissions: JSONObject? = null): JSONObject {
        val payload = JSONObject()
        enabled?.let { payload.put("enabled", it) }
        permissions?.let { payload.put("permissions", it) }
        val body = payload.toString().toRequestBody(jsonType)
        return call { Request.Builder().url("$baseUrl/api/v5/providers/${Uri.encode(provider)}").patch(body) }
            .use { parseObject(ensureSuccess(it, "Update provider"), "Update provider") }
    }

    suspend fun deleteProviderSecret(provider: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v5/providers/${Uri.encode(provider)}").delete() }
            .use { parseObject(ensureSuccess(it, "Delete provider"), "Delete provider") }

    suspend fun providerUsageV15(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v5/providers/usage/summary").get() }
            .use { parseObject(ensureSuccess(it, "Provider usage"), "Provider usage") }

    suspend fun cveSearchV15(query: String = "", year: Int = 2026, kevOnly: Boolean = false, limit: Int = 100): JSONObject {
        val url = "$baseUrl/api/v5/cve?q=${Uri.encode(query)}&year=$year&kev_only=$kevOnly&limit=${limit.coerceIn(1, 500)}"
        return call { Request.Builder().url(url).get() }.use { parseObject(ensureSuccess(it, "CVE search"), "CVE search") }
    }

    suspend fun cveSourcesV15(): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v5/cve/sources").get() }
            .use { parseObject(ensureSuccess(it, "CVE sources"), "CVE sources") }

    suspend fun cveDetailsV15(cveId: String): JSONObject =
        call { Request.Builder().url("$baseUrl/api/v5/cve/${Uri.encode(cveId)}").get() }
            .use { parseObject(ensureSuccess(it, "CVE details"), "CVE details") }

    suspend fun cyberIncidentsV15(): JSONArray =
        call { Request.Builder().url("$baseUrl/api/v5/cyber/incidents").get() }
            .use { parseArray(ensureSuccess(it, "Incidents"), "Incidents") }

    suspend fun aiCouncilV15(investigationId: String, question: String, mode: String = "smart"): JSONObject {
        val body = JSONObject()
            .put("investigation_id", investigationId)
            .put("question", question)
            .put("mode", mode)
            .toString().toRequestBody(jsonType)
        val aiClient = client.newBuilder().readTimeout(75, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
        return call(httpClient = aiClient) { Request.Builder().url("$baseUrl/api/v5/ai/council").post(body) }
            .use { parseObject(ensureSuccess(it, "AI Council"), "AI Council") }
    }

    private fun parseInvestigation(j: JSONObject): InvestigationSummary {
        val itemsArray = j.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.optJSONObject(i) ?: continue
                add(InvestigationItemSummary(
                    id = item.optString("id"),
                    entityType = item.optString("entity_type", "item"),
                    value = item.optString("value"),
                    depth = item.optInt("depth", 0),
                    status = item.optString("status", "unknown"),
                    confidence = item.optDouble("confidence", 0.0),
                    reason = item.optString("reason").takeIf { it.isNotBlank() && it != "null" }
                ))
            }
        }
        return InvestigationSummary(
            id = j.optString("id"),
            title = j.optString("title", "חקירה"),
            seedKind = j.optString("seed_kind"),
            seedValue = j.optString("seed_value"),
            status = j.optString("status", "unknown"),
            phase = j.optString("phase", "planning"),
            progress = j.optInt("progress", 0),
            authorizedNetworkTargets = j.optBoolean("authorized_network_targets", false),
            riskScore = j.optInt("risk_score", 0),
            summaryHe = j.optString("summary_he").takeIf { it.isNotBlank() && it != "null" },
            hypotheses = jsonObjects(j.optJSONArray("hypotheses") ?: JSONArray()),
            graph = j.optJSONObject("graph") ?: JSONObject(),
            metrics = j.optJSONObject("metrics") ?: JSONObject(),
            error = j.optString("error").takeIf { it.isNotBlank() && it != "null" },
            createdAt = j.optString("created_at").takeIf { it.isNotBlank() && it != "null" },
            updatedAt = j.optString("updated_at").takeIf { it.isNotBlank() && it != "null" },
            completedAt = j.optString("completed_at").takeIf { it.isNotBlank() && it != "null" },
            items = items
        )
    }

    private fun parseTargetWatch(j: JSONObject): TargetWatchSummary = TargetWatchSummary(
        id = j.optString("id"),
        target = j.optString("target"),
        targetHost = j.optString("target_host"),
        intervalMinutes = j.optInt("interval_minutes", 1440),
        enabled = j.optBoolean("enabled", true),
        nextRunAt = j.optString("next_run_at").takeIf { it.isNotBlank() && it != "null" },
        lastJobId = j.optString("last_job_id").takeIf { it.isNotBlank() && it != "null" },
        lastChangeAt = j.optString("last_change_at").takeIf { it.isNotBlank() && it != "null" }
    )

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
