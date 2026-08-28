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
    var loading by remember { mutableStateOf(true) }
    var loadingResult by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var deleteCandidate by remember { mutableStateOf<JobSummary?>(null) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        try {
            val boot = api.mobileBootstrap()
            jobs = boot.jobs
            selected?.let { current ->
                if (boot.jobs.none { it.id == current.id }) selected = null
            }
            boot.errors["jobs"]?.let { error = "רכיב ההיסטוריה בשרת לא זמין כרגע" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is SessionExpiredException) onSessionExpired()
            else error = e.message ?: "לא ניתן לטעון היסטוריה"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(selected?.id, selected?.status) {
        intelligence = null
        val current = selected ?: return@LaunchedEffect
        if (current.status.equals("completed", true)) {
            loadingResult = true
            try {
                intelligence = api.intelligence(current.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredException) onSessionExpired()
                else error = e.message ?: "לא ניתן לטעון את תוצאת הניתוח"
            } finally {
                loadingResult = false
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val current = selected
        if (current == null) {
            item {
                Text("היסטוריה", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "ניתוחים וסריקות קודמים",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { msg -> item { ErrorPanel(msg) { refreshKey++ } } }

            if (jobs.isEmpty() && !loading) {
                item {
                    SectionCard {
                        Text("אין עדיין ניתוחים או סריקות.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            items(jobs, key = { it.id }) { job ->
                SectionCard {
                    JobRow(job, onClick = { selected = job })
                    if (job.status.lowercase() in setOf("completed", "failed", "cancelled")) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = { deleteCandidate = job },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("מחק") }
                    }
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (current.fileType == "target") "תוצאת סריקה" else "תוצאת ניתוח",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            current.filename,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { selected = null; error = null }) { Text("חזרה") }
                }
            }

            if (loadingResult) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { msg -> item { ErrorPanel(msg) { selected = null; refreshKey++ } } }

            if (current.status.equals("completed", true)) {
                intelligence?.let { data -> item { IntelligenceDetailsColumn(intelligence = data) } }
            } else {
                item {
                    SectionCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("מצב", fontWeight = FontWeight.Bold)
                            StatusChip(current.status)
                        }
                        current.error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (current.status.equals("failed", true) || current.status.equals("cancelled", true)) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            api.retryJob(current.id)
                                            selected = null
                                            refreshKey++
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: SessionExpiredException) {
                                            onSessionExpired()
                                        } catch (e: Exception) {
                                            error = e.message ?: "לא ניתן להפעיל מחדש"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("נסה שוב") }
                        }
                    }
                }
            }

            if (current.status.lowercase() in setOf("completed", "failed", "cancelled")) {
                item {
                    OutlinedButton(
                        onClick = { deleteCandidate = current },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("מחק את ${if (current.fileType == "target") "הסריקה" else "הניתוח"}")
                    }
                }
            }
        }
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("מחיקה") },
            text = { Text("התוצאה והממצאים יימחקו לצמיתות.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteCandidate = null
                        scope.launch {
                            try {
                                api.deleteJob(candidate.id)
                                if (selected?.id == candidate.id) selected = null
                                refreshKey++
                            } catch (e: SessionExpiredException) {
                                onSessionExpired()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "המחיקה נכשלה"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("מחק") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("ביטול") } }
        )
    }
}
