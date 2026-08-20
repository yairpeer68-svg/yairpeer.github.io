package com.ghosteye.intelligence

import org.json.JSONObject

data class JobSummary(
    val id: String,
    val filename: String,
    val fileType: String?,
    val status: String,
    val progress: Int,
    val stage: String?,
    val error: String?,
    val createdAt: String?,
    val updatedAt: String?
)

data class Project(val id: String, val name: String)
data class CaseItem(val id: String, val title: String, val notes: String?)

data class CaseWorkspaceSummary(
    val id: String,
    val title: String,
    val status: String,
    val priority: String,
    val tags: List<String>,
    val archived: Boolean,
    val watchEnabled: Boolean,
    val watchIntervalHours: Int,
    val investigationCount: Int,
    val entityCount: Int,
    val latestRiskScore: Int?,
    val latestInvestigationId: String?,
    val latestInvestigationStatus: String?,
    val updatedAt: String?
)

data class AuditItem(
    val id: String,
    val action: String,
    val resourceType: String,
    val resourceId: String?,
    val createdAt: String?
)

data class GraphNode(val id: String, val label: String?, val kind: String?)
data class GraphEdge(val source: String, val target: String, val kind: String?)

data class ScanModule(
    val id: String,
    val name: String,
    val active: Boolean
)

data class TargetWatchSummary(
    val id: String,
    val target: String,
    val targetHost: String,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val nextRunAt: String?,
    val lastJobId: String?,
    val lastChangeAt: String?
)

data class IntelligenceSummary(
    val jobId: String,
    val filename: String,
    val fileType: String?,
    val sha256: String?,
    val riskScore: Int,
    val findingCount: Int,
    val evidenceCount: Int,
    val critical: Int,
    val high: Int,
    val medium: Int,
    val low: Int,
    val qualityScore: Int?,
    val aiSummary: String?,
    val findings: List<JSONObject>,
    val evidence: List<JSONObject>,
    val entities: List<JSONObject>,
    val relationships: List<JSONObject>,
    val metadata: JSONObject,
    val modules: JSONObject,
    val executive: JSONObject,
    val deepIntelligence: JSONObject,
    val changes: JSONObject,
    val fileIntelligence: JSONObject,
    val artifactChanges: JSONObject,
    val sbom: JSONObject,
    val pipeline: JSONObject,
    val integrity: JSONObject
)

data class MobileBootstrap(
    val version: String?,
    val summary: JSONObject,
    val jobs: List<JobSummary>,
    val projects: List<Project>,
    val graph: Pair<List<GraphNode>, List<GraphEdge>>,
    val audit: List<AuditItem>,
    val errors: Map<String, String>
)

data class InvestigationItemSummary(
    val id: String,
    val entityType: String,
    val value: String,
    val depth: Int,
    val status: String,
    val confidence: Double,
    val reason: String?
)

data class InvestigationSummary(
    val id: String,
    val title: String,
    val seedKind: String,
    val seedValue: String,
    val status: String,
    val phase: String,
    val progress: Int,
    val authorizedNetworkTargets: Boolean,
    val riskScore: Int,
    val summaryHe: String?,
    val hypotheses: List<JSONObject>,
    val graph: JSONObject,
    val metrics: JSONObject,
    val error: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val completedAt: String?,
    val items: List<InvestigationItemSummary> = emptyList()
)
