package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TargetScanScreen(
    baseUrl: String,
    modifier: Modifier = Modifier,
    onSessionExpired: () -> Unit,
    initialTarget: String = "",
    onInvestigationCreated: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    val networkAvailable = rememberNetworkAvailable()
    val appActive = rememberAppActive()

    var target by remember(initialTarget) { mutableStateOf(initialTarget) }
    var modules by remember { mutableStateOf<List<ScanModule>>(emptyList()) }
    var authorized by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<JobSummary?>(null) }
    var result by remember { mutableStateOf<IntelligenceSummary?>(null) }
    var watch by remember { mutableStateOf<TargetWatchSummary?>(null) }
    var busy by remember { mutableStateOf(false) }
    var watchBusy by remember { mutableStateOf(false) }
    var reportBusy by remember { mutableStateOf(false) }
    var loadingModules by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var reportId by remember { mutableStateOf<String?>(null) }
    var reportStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            modules = api.targetModules()
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "לא ניתן להכין את מנוע הסריקה"
        } finally {
            loadingModules = false
        }
    }

    suspend fun loadWatchFor(intelligence: IntelligenceSummary): TargetWatchSummary? {
        return try {
            api.targetWatches().firstOrNull { it.targetHost.equals(intelligence.filename, ignoreCase = true) }
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun monitor(jobId: String) {
        while (true) {
            if (!appActive || !networkAvailable) {
                delay(1000)
                continue
            }
            val current = api.status(jobId)
            job = current
            when (current.status.lowercase()) {
                "completed" -> {
                    val loaded = api.intelligence(jobId)
                    result = loaded
                    watch = loadWatchFor(loaded)
                    error = null
                    return
                }
                "failed" -> {
                    error = current.error ?: "סריקת היעד נכשלה"
                    return
                }
                "cancelled" -> return
            }
            delay(900)
        }
    }

    fun startFullScan() {
        if (busy || target.isBlank() || modules.isEmpty() || !authorized) return
        val previousJobId = if (result != null) job?.id else null
        scope.launch {
            busy = true
            error = null
            reportId = null
            reportStatus = null
            result = null
            job = null
            try {
                val allModules = modules.map { it.id }.toSet()
                val jobId = if (previousJobId != null) {
                    api.rescanTarget(previousJobId)
                } else {
                    api.startTargetScan(target, allModules)
                }
                monitor(jobId)
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = when (e) {
                    is ApiException -> "שגיאת שרת ${e.code}: ${e.message}"
                    else -> e.message ?: "הסריקה נכשלה"
                }
            } finally {
                busy = false
            }
        }
    }

    fun toggleWatch(enable: Boolean) {
        if (watchBusy) return
        val currentResult = result ?: return
        scope.launch {
            watchBusy = true
            error = null
            try {
                if (enable) {
                    watch = api.createTargetWatch(currentResult.filename, job?.id)
                } else {
                    watch?.let { api.deleteTargetWatch(it.id) }
                    watch = null
                }
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (enable && e.code == 409) {
                    watch = loadWatchFor(currentResult)
                } else {
                    error = "שגיאת שרת ${e.code}: ${e.message}"
                }
            } catch (e: Exception) {
                error = e.message ?: "לא ניתן לעדכן את המעקב"
            } finally {
                watchBusy = false
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
                reportStatus = if (verified.optBoolean("valid", false)) {
                    "הדוח נוצר והחתימה אומתה בהצלחה"
                } else {
                    "הדוח נוצר, אך אימות החתימה לא עבר"
                }
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
                            Icons.Rounded.Public,
                            contentDescription = null,
                            modifier = Modifier.padding(12.dp).size(28.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("סריקת דומיין / אתר", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "יעד אחד, כפתור אחד: איסוף, קישור, הערכת סיכון והשוואה לסריקות קודמות.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = target,
                    onValueChange = { value ->
                        val clean = value.trim()
                        if (clean != target) {
                            target = clean
                            job = null
                            result = null
                            watch = null
                            reportId = null
                            reportStatus = null
                        }
                        if (error != null) error = null
                    },
                    label = { Text("דומיין, URL או IP ציבורי") },
                    placeholder = { Text("example.com") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )

                if (target.isNotBlank()) {
                    Spacer(Modifier.height(9.dp))
                    AssistChip(onClick = {}, enabled = false, label = { Text(targetKindLabel(target)) })
                }

                Spacer(Modifier.height(12.dp))
                if (loadingModules) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("מכין את מנועי המודיעין…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        if (modules.isEmpty()) "מנוע הסריקה אינו זמין כרגע" else "Deep Scan • ${modules.size} מנועי בדיקה",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (modules.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Checkbox(checked = authorized, onCheckedChange = { authorized = it }, enabled = !busy)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "אני מאשר שהיעד בבעלותי או שיש לי הרשאה מפורשת לסרוק אותו.",
                        Modifier.padding(top = 11.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Button(
                onClick = { startFullScan() },
                enabled = !busy && target.isNotBlank() && modules.isNotEmpty() && authorized,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("סורק ומקשר את כל המידע…", fontWeight = FontWeight.Bold)
                } else {
                    Text(if (result == null) "סרוק הכל" else "סרוק שוב והשווה", fontWeight = FontWeight.Bold)
                }
            }
        }

        job?.let { current ->
            item {
                SectionCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("מצב הסריקה", fontWeight = FontWeight.Bold)
                        StatusChip(current.status)
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { current.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(current.stage ?: "אוסף מידע", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${current.progress}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (current.status.lowercase() in setOf("queued", "running", "processing")) {
                        Spacer(Modifier.height(10.dp))
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
                                        error = e.message ?: "לא ניתן לבטל"
                                    }
                                }
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("בטל סריקה") }
                    }
                }
            }
        }

        error?.let { item { ErrorPanel(it) } }

        result?.let { intelligence ->
            item {
                IntelligenceDetailsColumn(
                    intelligence = intelligence,
                    reportStatus = reportStatus,
                    reportId = reportId,
                    onSignReport = ::signCurrentReport,
                    onDeleteReport = if (reportBusy || reportId == null) null else ::deleteCurrentReport
                )
            }
            item {
                InvestigationLaunchCard(
                    baseUrl = baseUrl,
                    seedJobId = intelligence.jobId,
                    networkAlreadyAuthorized = true,
                    onSessionExpired = onSessionExpired,
                    onCreated = onInvestigationCreated
                )
            }
            item {
                TargetIntelligenceSourcesCard(
                    baseUrl = baseUrl,
                    target = target,
                    onSessionExpired = onSessionExpired
                )
            }
            item {
                SectionCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("מעקב יומי", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (watch != null) {
                                    if (watch?.lastChangeAt != null) "פעיל • זוהה שינוי בסריקה אוטומטית" else "פעיל • Ghost Eye יסרוק שוב פעם ביום"
                                } else {
                                    "אופציונלי • סריקה חוזרת אוטומטית פעם ביום"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (watchBusy) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Switch(checked = watch?.enabled == true, onCheckedChange = { toggleWatch(it) })
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("מחק את הסריקה") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("מחיקת סריקה") },
            text = { Text("התוצאה והממצאים של הסריקה יימחקו לצמיתות. מעקב יומי, אם הופעל, יישאר פעיל עד שתכבה אותו.") },
            confirmButton = {
                Button(
                    onClick = {
                        val id = job?.id
                        confirmDelete = false
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
                                error = e.message ?: "המחיקה נכשלה"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("מחק") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ביטול") } }
        )
    }
}

private fun targetKindLabel(value: String): String {
    val clean = value.trim()
    return when {
        clean.startsWith("http://", true) || clean.startsWith("https://", true) -> "אתר / URL"
        Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$").matches(clean) -> "כתובת IP"
        else -> "דומיין"
    }
}
