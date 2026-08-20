package com.ghosteye.intelligence

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AnalysisScreen(
    baseUrl: String,
    modifier: Modifier = Modifier,
    onSessionExpired: () -> Unit,
    onPivotTarget: (String) -> Unit = {},
    onInvestigationCreated: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()

    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<JobSummary?>(null) }
    var result by remember { mutableStateOf<IntelligenceSummary?>(null) }
    var busy by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableIntStateOf(0) }
    var confirmDeleteJob by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reportId by remember { mutableStateOf<String?>(null) }
    var reportStatus by remember { mutableStateOf<String?>(null) }
    var reportBusy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = api.displayName(uri)
            job = null
            result = null
            reportId = null
            reportStatus = null
            error = null
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    suspend fun monitorJob(jobId: String) {
        while (true) {
            val current = api.status(jobId)
            job = current
            when (current.status.lowercase()) {
                "completed" -> {
                    result = api.intelligence(jobId)
                    error = null
                    return
                }
                "failed" -> {
                    error = current.error ?: "הניתוח נכשל"
                    return
                }
                "cancelled" -> {
                    error = null
                    return
                }
            }
            delay(900)
        }
    }

    fun startAnalysis() {
        val uri = selectedUri ?: return
        if (busy) return
        scope.launch {
            busy = true
            error = null
            result = null
            job = null
            reportId = null
            reportStatus = null
            uploadProgress = 0
            try {
                val jobId = api.upload(uri) { uploadProgress = it }
                monitorJob(jobId)
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

    fun signCurrentReport() {
        val currentJobId = job?.id ?: return
        if (reportBusy) return
        scope.launch {
            reportBusy = true
            reportStatus = "יוצר ומאמת דוח…"
            try {
                val signed = api.signReport(currentJobId)
                val id = signed.optString("report_id").ifBlank { signed.optString("id") }
                if (id.isBlank()) throw IllegalStateException("השרת לא החזיר מזהה דוח")
                reportId = id
                val verified = api.verifyReport(id)
                reportStatus = if (verified.optBoolean("valid", false)) "הדוח נוצר והחתימה אומתה בהצלחה" else "הדוח נוצר, אך אימות החתימה לא עבר"
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportStatus = e.message ?: "יצירת הדוח נכשלה"
            } finally {
                reportBusy = false
            }
        }
    }

    fun deleteCurrentReport() {
        val id = reportId ?: return
        if (reportBusy) return
        scope.launch {
            reportBusy = true
            try {
                api.deleteReport(id)
                reportId = null
                reportStatus = "הדוח נמחק"
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportStatus = e.message ?: "מחיקת הדוח נכשלה"
            } finally {
                reportBusy = false
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            Icons.Rounded.Description,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp).size(28.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("ניתוח קובץ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "APK, AAB, DEX, EXE, DLL, ELF, ZIP ועוד — ניתוח סטטי מלא ומסודר.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (selectedName == null) "בחר קובץ" else "בחר קובץ אחר")
                }

                selectedName?.let { name ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ) {
                        Text(
                            name,
                            Modifier.fillMaxWidth().padding(14.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        item {
            Button(
                onClick = { startAnalysis() },
                enabled = selectedUri != null && !busy,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("מנתח את כל המידע…", fontWeight = FontWeight.Bold)
                } else {
                    Text(if (result == null) "נתח את כל המידע" else "נתח גרסה חדשה / שוב", fontWeight = FontWeight.Bold)
                }
            }
        }

        if (busy && job == null && uploadProgress > 0) {
            item {
                SectionCard {
                    Text("מעלה את הקובץ", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { uploadProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("$uploadProgress%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { current.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(current.stage ?: "מעבד", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${current.progress}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (current.status.lowercase() in setOf("queued", "running", "processing")) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        api.cancelJob(current.id)
                                        job = api.status(current.id)
                                    } catch (e: SessionExpiredException) {
                                        onSessionExpired()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = e.message ?: "לא ניתן לבטל את הניתוח"
                                    }
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("בטל ניתוח") }
                    } else if (current.status.equals("failed", true) || current.status.equals("cancelled", true)) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (!busy) scope.launch {
                                    busy = true
                                    error = null
                                    result = null
                                    try {
                                        api.retryJob(current.id)
                                        monitorJob(current.id)
                                    } catch (e: SessionExpiredException) {
                                        onSessionExpired()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = e.message ?: "לא ניתן להפעיל מחדש"
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("נסה שוב") }
                    }
                }
            }
        }

        error?.let { msg -> item { ErrorPanel(msg) } }

        result?.let { intelligence ->
            item {
                IntelligenceDetailsColumn(
                    intelligence = intelligence,
                    reportStatus = reportStatus,
                    reportId = reportId,
                    onSignReport = ::signCurrentReport,
                    onDeleteReport = if (reportId == null || reportBusy) null else ::deleteCurrentReport,
                    onPivotTarget = onPivotTarget
                )
            }
            item {
                InvestigationLaunchCard(
                    baseUrl = baseUrl,
                    seedJobId = intelligence.jobId,
                    networkAlreadyAuthorized = false,
                    onSessionExpired = onSessionExpired,
                    onCreated = onInvestigationCreated
                )
            }
            item {
                OutlinedButton(
                    onClick = { confirmDeleteJob = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("מחק את הניתוח") }
            }
        }
    }

    if (confirmDeleteJob) {
        AlertDialog(
            onDismissRequest = { confirmDeleteJob = false },
            title = { Text("מחיקת ניתוח") },
            text = { Text("התוצאה והממצאים של הניתוח יימחקו לצמיתות.") },
            confirmButton = {
                Button(
                    onClick = {
                        val id = job?.id
                        confirmDeleteJob = false
                        if (id != null) scope.launch {
                            try {
                                api.deleteJob(id)
                                job = null
                                result = null
                                reportId = null
                                reportStatus = null
                                error = null
                            } catch (e: SessionExpiredException) {
                                onSessionExpired()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "מחיקת הניתוח נכשלה"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("מחק") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteJob = false }) { Text("ביטול") } }
        )
    }
}
