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

data class AuditItem(
    val id: String,
    val action: String,
    val resourceType: String,
    val resourceId: String?,
    val createdAt: String?
)

data class GraphNode(val id: String, val label: String?, val kind: String?)
data class GraphEdge(val source: String, val target: String, val kind: String?)

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
    val findings: List<JSONObject>
)
