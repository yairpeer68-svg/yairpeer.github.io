package com.ghosteye.intelligence

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun DashboardScreen(
    baseUrl: String,
    modifier: Modifier = Modifier,
    onNewAnalysis: () -> Unit,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    val networkAvailable = rememberNetworkAvailable()
    val appActive = rememberAppActive()
    var summary by remember { mutableStateOf<JSONObject?>(null) }
    var jobs by remember { mutableStateOf<List<JobSummary>>(emptyList()) }
    var graph by remember { mutableStateOf<Pair<List<GraphNode>, List<GraphEdge>>>(emptyList<GraphNode>() to emptyList()) }
    var audit by remember { mutableStateOf<List<AuditItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var deleteAuditCandidate by remember { mutableStateOf<AuditItem?>(null) }

    // Keep the dashboard live while it is visible without requiring manual taps.
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            // Never cancel a slow but healthy bootstrap just because the periodic
            // refresh interval elapsed. A cancellation loop here could otherwise
            // prevent Dashboard data from ever loading on a slow mobile network.
            if (!loading && networkAvailable && appActive) refreshKey++
        }
    }

    LaunchedEffect(refreshKey, networkAvailable, appActive) {
        if (!appActive) return@LaunchedEffect
        if (!networkAvailable) {
            loading = false
            error = if (summary == null) "אין כרגע חיבור אינטרנט מאומת. הנתונים ייטענו אוטומטית כשהחיבור יחזור." else "אין כרגע חיבור אינטרנט; מוצגים הנתונים האחרונים שנטענו."
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val loaded = api.mobileBootstrap()
            summary = loaded.summary
            jobs = loaded.jobs
            graph = loaded.graph
            audit = loaded.audit
            if (loaded.errors.isNotEmpty()) {
                val names = loaded.errors.keys.joinToString(", ")
                error = "השרת החזיר מידע חלקי ($names). הנתונים שכן זמינים מוצגים כרגיל."
            }
        } catch (e: CancellationException) {
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
            val active = summary?.optInt("queued_or_running", 0) ?: jobs.count {
                it.status.equals("queued", true) || it.status.equals("running", true) || it.status.equals("processing", true)
            }
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
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
                        IconButton(onClick = { deleteAuditCandidate = event }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "מחק אירוע", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    deleteAuditCandidate?.let { event ->
        AlertDialog(
            onDismissRequest = { deleteAuditCandidate = null },
            title = { Text("מחיקת אירוע Audit") },
            text = { Text("האירוע יוסר לצמיתות מהיסטוריית ה־Audit.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteAuditCandidate = null
                        scope.launch {
                            try {
                                api.deleteAudit(event.id)
                                audit = audit.filterNot { it.id == event.id }
                            } catch (e: SessionExpiredException) {
                                onSessionExpired()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "מחיקת האירוע נכשלה"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("מחק") }
            },
            dismissButton = { TextButton(onClick = { deleteAuditCandidate = null }) { Text("ביטול") } }
        )
    }
}

private fun friendlyDashboardError(t: Throwable?): String = when (t) {
    null -> "נסה לרענן בעוד רגע."
    is ApiException -> buildString { append("השרת החזיר ${t.code}: ${t.message ?: "שגיאה"}"); t.requestId?.let { append(" • מזהה: ${it.take(12)}") } }
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
