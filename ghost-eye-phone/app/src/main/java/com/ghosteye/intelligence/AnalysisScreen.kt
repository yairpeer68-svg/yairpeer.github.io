package com.ghosteye.intelligence

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AnalysisScreen(baseUrl: String, modifier: Modifier = Modifier, onSessionExpired: () -> Unit) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<JobSummary?>(null) }
    var result by remember { mutableStateOf<IntelligenceSummary?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = api.displayName(uri)
            job = null
            result = null
            error = null
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun startAnalysis() {
        val uri = selectedUri ?: return
        if (busy) return
        scope.launch {
            busy = true
            error = null
            result = null
            try {
                val jobId = api.upload(uri)
                while (true) {
                    val current = api.status(jobId)
                    job = current
                    when (current.status.lowercase()) {
                        "completed" -> {
                            result = api.intelligence(jobId)
                            break
                        }
                        "failed", "cancelled" -> {
                            error = current.error ?: "הניתוח לא הושלם"
                            break
                        }
                    }
                    delay(900)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredException) {
                    onSessionExpired()
                } else {
                    error = when (e) {
                        is ApiException -> "שגיאת שרת ${e.code}: ${e.message}"
                        else -> e.message ?: "הניתוח נכשל"
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { PageTitle("ניתוח קובץ", "APK, EXE וקבצים נתמכים נוספים — העלאה מאובטחת לשרת") }

        item {
            SectionCard {
                Text("1. בחר קובץ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (selectedName == null) "בחר קובץ מהטלפון" else "בחר קובץ אחר")
                }
                selectedName?.let {
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                        Text(it, Modifier.fillMaxWidth().padding(14.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item {
            Button(
                onClick = { startAnalysis() },
                enabled = selectedUri != null && !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("מנתח…")
                } else {
                    Text("התחל ניתוח", fontWeight = FontWeight.Bold)
                }
            }
        }

        job?.let { current ->
            item {
                SectionCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("מצב הניתוח", fontWeight = FontWeight.Bold)
                        StatusChip(current.status)
                    }
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { current.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(current.stage ?: "מעבד", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${current.progress}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        error?.let { msg -> item { ErrorPanel(msg) } }
        result?.let { intelligence ->
            item { IntelligenceResultHeader(intelligence) }
            if (!intelligence.aiSummary.isNullOrBlank()) {
                item {
                    SectionCard {
                        Text("AI Intelligence", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(intelligence.aiSummary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { Text("ממצאים", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            if (intelligence.findings.isEmpty()) {
                item { SectionCard { Text("לא נמצאו ממצאים בדוח הנוכחי.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            } else {
                items(intelligence.findings.take(30)) { finding -> FindingCard(finding) }
            }
        }
    }
}

@Composable
fun IntelligenceResultHeader(intelligence: IntelligenceSummary) {
    SectionCard {
        Text(intelligence.filename, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(5.dp))
        Text(
            intelligence.fileType ?: "סוג לא ידוע",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        intelligence.sha256?.let {
            Spacer(Modifier.height(5.dp))
            Text("SHA-256 ${it.take(16)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Risk", intelligence.riskScore.toString(), Modifier.weight(1f), riskAccent(intelligence.riskScore))
            MetricCard("Findings", intelligence.findingCount.toString(), Modifier.weight(1f))
            MetricCard("Evidence", intelligence.evidenceCount.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Critical ${intelligence.critical} • High ${intelligence.high} • Medium ${intelligence.medium} • Low ${intelligence.low}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FindingCard(finding: JSONObject) {
    val title = finding.optString("title").ifBlank { finding.optString("type", "ממצא") }
    val severity = finding.optString("severity", "info")
    val confidence = finding.optString("confidence", "unknown")
    val risk = finding.optJSONObject("risk")?.optInt("score", 0) ?: 0
    val description = listOf("description", "detail", "value", "match").firstNotNullOfOrNull { key ->
        finding.optString(key).takeIf { it.isNotBlank() }
    }
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(10.dp))
            Surface(shape = RoundedCornerShape(999.dp), color = riskAccent(risk).copy(alpha = 0.18f)) {
                Text("$risk", Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = riskAccent(risk), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text("$severity • confidence: $confidence", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        description?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun riskAccent(score: Int) = when {
    score >= 90 -> androidx.compose.ui.graphics.Color(0xFFFF5A67)
    score >= 70 -> androidx.compose.ui.graphics.Color(0xFFFF8A4C)
    score >= 45 -> androidx.compose.ui.graphics.Color(0xFFFFC857)
    else -> androidx.compose.ui.graphics.Color(0xFF6EE7A8)
}
