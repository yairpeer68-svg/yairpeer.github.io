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
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(baseUrl: String, modifier: Modifier = Modifier, onSessionExpired: () -> Unit) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var jobs by remember { mutableStateOf<List<JobSummary>>(emptyList()) }
    var selected by remember { mutableStateOf<JobSummary?>(null) }
    var intelligence by remember { mutableStateOf<IntelligenceSummary?>(null) }
    var comparison by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        try {
            val boot = api.mobileBootstrap()
            jobs = boot.jobs
            boot.errors["jobs"]?.let { error = "רכיב ההיסטוריה בשרת לא זמין כרגע" }
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (e is SessionExpiredException) onSessionExpired() else error = e.message ?: "לא ניתן לטעון היסטוריה" }
        finally { loading = false }
    }

    LaunchedEffect(selected?.id) {
        intelligence = null
        comparison = null
        val job = selected ?: return@LaunchedEffect
        if (job.status.equals("completed", true)) {
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
                if (job.status.equals("completed", true)) {
                    if (intelligence == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        val loaded = intelligence
                        if (loaded != null) {
                            IntelligenceResultHeader(loaded)
                            val previous = jobs.firstOrNull { other ->
                                other.id != job.id && other.status.equals("completed", true) &&
                                    other.filename.equals(job.filename, ignoreCase = true) &&
                                    (job.createdAt == null || other.createdAt == null || other.createdAt < job.createdAt)
                            }
                            if (previous != null) {
                                Spacer(Modifier.height(8.dp))
                                SectionCard {
                                    Text("השוואה היסטורית", fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(6.dp))
                                    Text("השווה לניתוח קודם של אותו קובץ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(10.dp))
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            try { comparison = api.compareJobs(previous.id, job.id) }
                                            catch (e: CancellationException) { throw e }
                                            catch (e: SessionExpiredException) { onSessionExpired() }
                                            catch (e: Exception) { error = e.message ?: "ההשוואה נכשלה" }
                                        }
                                    }) { Text("השווה לגרסה הקודמת") }
                                    comparison?.let { diff ->
                                        Spacer(Modifier.height(10.dp))
                                        // History diff v1 groups finding changes under
                                        // `findings` and evidence changes under `evidence`.
                                        // Older servers used flatter fields, so keep a
                                        // compatibility fallback instead of displaying
                                        // misleading zeroes for a valid comparison.
                                        val findings = diff.optJSONObject("findings")
                                        val added = findings?.optJSONArray("added")?.length()
                                            ?: diff.optJSONArray("added")?.length()
                                            ?: diff.optInt("added_count", 0)
                                        val removed = findings?.optJSONArray("removed")?.length()
                                            ?: diff.optJSONArray("removed")?.length()
                                            ?: diff.optInt("removed_count", 0)
                                        val changed = findings?.optJSONArray("changed")?.length()
                                            ?: diff.optJSONArray("changed")?.length()
                                            ?: diff.optInt("changed_count", 0)
                                        Text("נוספו $added • הוסרו $removed • השתנו $changed", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
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
                        if (job.status.equals("failed", true) || job.status.equals("cancelled", true)) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        api.retryJob(job.id)
                                        selected = null
                                        refreshKey++
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: SessionExpiredException) {
                                        onSessionExpired()
                                    } catch (e: Exception) {
                                        error = e.message ?: "לא ניתן להפעיל מחדש את הניתוח"
                                    }
                                }
                            }) { Text("נסה שוב") }
                        }
                    }
                }
            }
        }
    }
}
