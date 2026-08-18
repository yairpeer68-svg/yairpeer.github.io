package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
fun HistoryScreen(baseUrl: String, modifier: Modifier = Modifier, onSessionExpired: () -> Unit) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    var jobs by remember { mutableStateOf<List<JobSummary>>(emptyList()) }
    var selected by remember { mutableStateOf<JobSummary?>(null) }
    var intelligence by remember { mutableStateOf<IntelligenceSummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        try { jobs = api.jobs() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (e is SessionExpiredException) onSessionExpired() else error = e.message ?: "לא ניתן לטעון היסטוריה" }
        finally { loading = false }
    }

    LaunchedEffect(selected?.id) {
        intelligence = null
        val job = selected ?: return@LaunchedEffect
        if (job.status == "completed") {
            try { intelligence = api.intelligence(job.id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (e is SessionExpiredException) onSessionExpired() else error = e.message ?: "לא ניתן לטעון את תוצאת הניתוח" }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { PageTitle("היסטוריה", "כל הניתוחים שבוצעו בחשבון הפרטי שלך") }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { msg -> item { ErrorPanel(msg) { refreshKey++ } } }

        if (jobs.isEmpty() && !loading) {
            item { SectionCard { Text("אין ניתוחים בהיסטוריה עדיין.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }

        items(jobs, key = { it.id }) { job ->
            JobRow(job) { selected = if (selected?.id == job.id) null else job }
            if (selected?.id == job.id) {
                Spacer(Modifier.height(8.dp))
                if (job.status == "completed") {
                    if (intelligence == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        val loaded = intelligence
                        if (loaded != null) {
                            IntelligenceResultHeader(loaded)
                        }
                        if (loaded != null && loaded.findings.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            SectionCard {
                                Text("Top findings", fontWeight = FontWeight.Bold)
                                loaded.findings.take(5).forEach { f ->
                                    Spacer(Modifier.height(8.dp))
                                    Text("• ${f.optString("title").ifBlank { f.optString("type", "ממצא") }}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                } else {
                    SectionCard {
                        Text("סטטוס: ${job.status}", fontWeight = FontWeight.SemiBold)
                        job.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}
