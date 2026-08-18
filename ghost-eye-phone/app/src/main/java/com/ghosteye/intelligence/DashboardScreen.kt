package com.ghosteye.intelligence

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class DashboardLoad(
    val summary: Result<JSONObject>,
    val jobs: Result<List<JobSummary>>,
    val graph: Result<Pair<List<GraphNode>, List<GraphEdge>>>,
    val audit: Result<List<AuditItem>>
)

private suspend fun <T> isolatedApiCall(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

@Composable
fun DashboardScreen(
    baseUrl: String,
    modifier: Modifier = Modifier,
    onNewAnalysis: () -> Unit,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var jobs by remember { mutableStateOf<List<JobSummary>>(emptyList()) }
    var graph by remember { mutableStateOf<Pair<List<GraphNode>, List<GraphEdge>>>(emptyList<GraphNode>() to emptyList()) }
    var audit by remember { mutableStateOf<List<AuditItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        try {
            // IMPORTANT: supervisorScope prevents one optional dashboard endpoint
            // (for example Audit or Graph) from cancelling the entire screen.
            val loaded = supervisorScope {
                val s = async { isolatedApiCall { api.dashboardSummary() } }
                val j = async { isolatedApiCall { api.jobs() } }
                val g = async { isolatedApiCall { api.graph() } }
                val a = async { isolatedApiCall { api.audit(8) } }
                DashboardLoad(s.await(), j.await(), g.await(), a.await())
            }

            val failures = listOf(loaded.summary, loaded.jobs, loaded.graph, loaded.audit)
                .mapNotNull { it.exceptionOrNull() }

            if (failures.any { it is SessionExpiredException }) {
                onSessionExpired()
                return@LaunchedEffect
            }

            loaded.summary.getOrNull()?.let { summary = it }
            loaded.jobs.getOrNull()?.let { jobs = it }
            loaded.graph.getOrNull()?.let { graph = it }
            loaded.audit.getOrNull()?.let { audit = it }

            if (failures.isNotEmpty()) {
                val names = buildList {
                    if (loaded.summary.isFailure) add("Dashboard")
                    if (loaded.jobs.isFailure) add("Jobs")
                    if (loaded.graph.isFailure) add("Graph")
                    if (loaded.audit.isFailure) add("Audit")
                }
                val first = failures.firstOrNull()
                error = "חלק מהמידע לא נטען (${names.joinToString(", ")}). " + friendlyDashboardError(first)
            }
        } catch (e: CancellationException) {
            // Normal when the user changes tab/backgrounds the app. Never render
            // "Parent job is Cancelling" as an application error.
            throw e
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: Exception) {
            error = friendlyDashboardError(e)
        } finally {
            loading = false
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { PageTitle("מרכז הבקרה", "תמונת מצב חיה של מערכת ה־Intelligence") }

        if (loading && summary == null) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }

        error?.let { msg -> item { ErrorPanel(msg) { refreshKey++ } } }

        item {
            Button(
                onClick = onNewAnalysis,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("ניתוח חדש", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }

        item {
            val total = summary?.optInt("total_jobs", 0) ?: jobs.size
            val completed = summary?.optInt("completed", 0) ?: jobs.count { it.status.equals("completed", true) }
            val active = summary?.optInt("queued_or_running", 0) ?: jobs.count { it.status.equals("queued", true) || it.status.equals("running", true) }
            val failed = summary?.optInt("failed", 0) ?: jobs.count { it.status.equals("failed", true) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("כל הניתוחים", total.toString(), Modifier.weight(1f))
                    MetricCard("הושלמו", completed.toString(), Modifier.weight(1f), Color(0xFF6EE7A8))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("פעילים עכשיו", active.toString(), Modifier.weight(1f), Color(0xFF61D6FF))
                    MetricCard("נכשלו", failed.toString(), Modifier.weight(1f), MaterialTheme.colorScheme.error)
                }
            }
        }

        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Knowledge Graph", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("${graph.first.size} nodes • ${graph.second.size} edges", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                GraphPreview(graph.first, graph.second)
                if (graph.first.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("הגרף יתמלא אוטומטית אחרי ניתוחים.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item { Text("ניתוחים אחרונים", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (jobs.isEmpty()) {
            item { SectionCard { Text("עדיין אין ניתוחים. בחר קובץ והתחל ניתוח ראשון.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        } else {
            items(jobs.take(5), key = { it.id }) { job -> JobRow(job) }
        }

        item { Text("פעילות אחרונה", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (audit.isEmpty()) {
            item { SectionCard { Text("אין אירועי Audit להצגה כרגע.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        } else {
            items(audit.take(6), key = { it.id }) { event ->
                SectionCard {
                    Text(event.action, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(event.resourceType, event.resourceId).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    event.createdAt?.let {
                        Spacer(Modifier.height(3.dp))
                        Text(it.replace("T", " ").take(19), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun friendlyDashboardError(t: Throwable?): String = when (t) {
    null -> "נסה לרענן בעוד רגע."
    is ApiException -> "השרת החזיר ${t.code}: ${t.message ?: "שגיאה"}"
    else -> t.message?.takeIf { it.isNotBlank() && !it.contains("Cancelling", ignoreCase = true) }
        ?: "נסה לרענן בעוד רגע."
}

@Composable
private fun GraphPreview(nodes: List<GraphNode>, edges: List<GraphEdge>) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val edgeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    Canvas(Modifier.fillMaxWidth().height(210.dp)) {
        if (nodes.isEmpty()) return@Canvas
        val count = nodes.take(24).size
        if (count == 0) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = minOf(size.width, size.height) * 0.37f
        val positions = nodes.take(24).mapIndexed { index, node ->
            val angle = (2.0 * PI * index / count) - PI / 2
            node.id to Offset(
                center.x + (cos(angle) * radius).toFloat(),
                center.y + (sin(angle) * radius).toFloat()
            )
        }.toMap()

        edges.take(60).forEach { edge ->
            val a = positions[edge.source]
            val b = positions[edge.target]
            if (a != null && b != null) drawLine(edgeColor, a, b, strokeWidth = 2f)
        }
        positions.entries.forEachIndexed { index, entry ->
            drawCircle(if (index % 3 == 0) secondary else primary, radius = 8f, center = entry.value)
            drawCircle(Color.White.copy(alpha = 0.65f), radius = 3f, center = entry.value)
        }
    }
}
