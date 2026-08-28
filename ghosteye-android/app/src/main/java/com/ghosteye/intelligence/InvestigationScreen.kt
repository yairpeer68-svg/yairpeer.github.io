package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
import org.json.JSONObject

@Composable
fun InvestigationLaunchCard(
    baseUrl: String,
    seedJobId: String,
    networkAlreadyAuthorized: Boolean,
    onSessionExpired: () -> Unit,
    onCreated: (String) -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var allowNetwork by remember(seedJobId, networkAlreadyAuthorized) { mutableStateOf(networkAlreadyAuthorized) }
    var busy by remember(seedJobId) { mutableStateOf(false) }
    var message by remember(seedJobId) { mutableStateOf<String?>(null) }
    var failed by remember(seedJobId) { mutableStateOf(false) }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("חקירה אוטומטית", fontWeight = FontWeight.Bold)
                Text(
                    "Ghost Eye יחבר ממצאים, קשרים וראיות לחקירה אחת מוגבלת ובטוחה.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (!networkAlreadyAuthorized) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(checked = allowNetwork, onCheckedChange = { allowNetwork = it }, enabled = !busy)
                Spacer(Modifier.width(6.dp))
                Text(
                    "אני מאשר סריקה פעילה של דומיינים/IP ציבוריים שיימצאו בקובץ. בלי אישור, הם יישמרו כראיות בלבד ולא ייסרקו.",
                    modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                "משתמש באישור שכבר נתת לסריקת היעד. כתובות פרטיות/מקומיות נשארות חסומות.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (busy) return@Button
                scope.launch {
                    busy = true
                    failed = false
                    message = "יוצר חקירה…"
                    try {
                        val created = api.createInvestigationFromJob(seedJobId, allowNetwork)
                        message = "החקירה נוצרה"
                        onCreated(created.id)
                    } catch (e: SessionExpiredException) {
                        onSessionExpired()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failed = true
                        message = e.message ?: "יצירת החקירה נכשלה"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(9.dp))
            }
            Text("פתח חקירה אוטומטית")
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun InvestigationsScreen(
    baseUrl: String,
    modifier: Modifier = Modifier,
    initialInvestigationId: String? = null,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    val networkAvailable = rememberNetworkAvailable()
    val appActive = rememberAppActive()
    var investigations by remember { mutableStateOf<List<InvestigationSummary>>(emptyList()) }
    var selectedId by remember(initialInvestigationId) { mutableStateOf(initialInvestigationId) }
    var selected by remember { mutableStateOf<InvestigationSummary?>(null) }
    var loading by remember { mutableStateOf(true) }
    var actionBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var compareResult by remember { mutableStateOf<JSONObject?>(null) }
    var reportMessage by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    suspend fun refreshList() {
        try {
            investigations = api.investigations()
            error = null
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "לא ניתן לטעון חקירות"
        } finally {
            loading = false
        }
    }

    suspend fun refreshSelected() {
        val id = selectedId ?: return
        try {
            selected = api.investigation(id)
            error = null
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "לא ניתן לטעון את החקירה"
        }
    }

    LaunchedEffect(Unit) { refreshList() }
    LaunchedEffect(selectedId) {
        selected = null
        compareResult = null
        reportMessage = null
        if (selectedId != null) refreshSelected()
    }
    LaunchedEffect(selectedId, selected?.status, networkAvailable, appActive) {
        while (selectedId != null && selected?.status?.lowercase() in setOf("running", "paused")) {
            if (appActive && networkAvailable && selected?.status?.lowercase() == "running") refreshSelected()
            delay(if (appActive && networkAvailable) 2500 else 5000)
        }
    }

    if (selectedId == null) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        PageTitle("חקירות", "חקירה אחת מחברת קובץ, דומיינים, IP, ראיות ומסקנות.")
                    }
                    IconButton(onClick = { scope.launch { loading = true; refreshList() } }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "רענון")
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { msg -> item { ErrorPanel(msg) { scope.launch { loading = true; refreshList() } } } }
            if (!loading && investigations.isEmpty() && error == null) {
                item {
                    SectionCard {
                        Text("אין עדיין חקירות", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("פתח תוצאת קובץ או דומיין ולחץ ‘פתח חקירה אוטומטית’.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(investigations, key = { it.id }) { inv ->
                Card(
                    onClick = { selectedId = inv.id },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(inv.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(inv.seedValue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            StatusChip(inv.status)
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(progress = { inv.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(phaseLabel(inv.phase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (inv.status == "completed") "סיכון ${inv.riskScore}/100" else "${inv.progress}%", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        return
    }

    val inv = selected
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = { selectedId = null; selected = null; compareResult = null }) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("כל החקירות")
            }
        }
        if (inv == null) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { msg -> item { ErrorPanel(msg) { scope.launch { refreshSelected() } } } }
        } else {
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(inv.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(3.dp))
                            Text(inv.seedValue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusChip(inv.status)
                    }
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(progress = { inv.progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(phaseLabel(inv.phase), style = MaterialTheme.typography.bodySmall)
                        Text("${inv.progress}%", fontWeight = FontWeight.SemiBold)
                    }
                    if (inv.status == "completed") {
                        Spacer(Modifier.height(12.dp))
                        Text("ציון סיכון ${inv.riskScore}/100", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = riskColor(inv.riskScore))
                    }
                    inv.error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }

            inv.summaryHe?.takeIf { it.isNotBlank() }?.let { summary ->
                item {
                    SectionCard {
                        Text("סיכום החקירה", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                AutonomyConsoleCard(
                    baseUrl = baseUrl,
                    investigationId = inv.id,
                    investigationStatus = inv.status,
                    onSessionExpired = onSessionExpired
                )
            }

            item {
                AutonomousIntelligenceCard(
                    baseUrl = baseUrl,
                    investigationId = inv.id,
                    status = inv.status,
                    onSessionExpired = onSessionExpired
                )
            }

            item {
                IntelligenceSourcesCard(
                    baseUrl = baseUrl,
                    investigationId = inv.id,
                    onSessionExpired = onSessionExpired
                )
            }

            item {
                GlobalKnowledgeCard(
                    baseUrl = baseUrl,
                    investigationId = inv.id,
                    onSessionExpired = onSessionExpired
                )
            }

            item {
                AiCouncilCard(
                    baseUrl = baseUrl,
                    investigationId = inv.id,
                    onSessionExpired = onSessionExpired
                )
            }

            item {
                VerifiedInvestigationCard(
                    baseUrl = baseUrl,
                    investigationId = inv.id,
                    onSessionExpired = onSessionExpired
                )
            }

            item {
                val nodes = inv.graph.optJSONArray("nodes")?.length() ?: 0
                val edges = inv.graph.optJSONArray("edges")?.length() ?: 0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard("פריטים", inv.items.size.toString(), Modifier.weight(1f))
                    MetricCard("ישויות", nodes.toString(), Modifier.weight(1f))
                    MetricCard("קשרים", edges.toString(), Modifier.weight(1f))
                }
            }

            if (inv.hypotheses.isNotEmpty()) {
                item {
                    SectionCard {
                        Text("מסקנות והשערות", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        inv.hypotheses.take(8).forEachIndexed { index, h ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                            val text = h.optString("statement_he").ifBlank { h.optString("statement") }.ifBlank { "השערה מבוססת ראיות" }
                            val confidence = (h.optDouble("confidence", 0.0) * 100).toInt().coerceIn(0, 100)
                            val verification = h.optJSONObject("verification")
                            val verificationStatus = verification?.optString("status").orEmpty()
                            val verificationLabel = when (verificationStatus) {
                                "corroborated" -> "מאומתת בראיות"
                                "partially_corroborated" -> "מאומתת חלקית"
                                "insufficient_evidence" -> "אין מספיק ראיות"
                                else -> "השערה, לא עובדה"
                            }
                            val coverage = ((verification?.optDouble("evidence_coverage", 0.0) ?: 0.0) * 100).toInt().coerceIn(0, 100)
                            val missing = verification?.optJSONArray("missing_job_ids")?.length() ?: 0
                            Text(text, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text("ביטחון $confidence% • $verificationLabel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (verification != null) {
                                Text("כיסוי ראיות $coverage%${if (missing > 0) " • $missing הפניות חסרות" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (inv.items.isNotEmpty()) {
                item {
                    SectionCard {
                        Text("ציר החקירה", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        inv.items.take(20).forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.value, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                    Text("${entityLabel(item.entityType)} • עומק ${item.depth}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                StatusChip(item.status)
                            }
                        }
                        if (inv.items.size > 20) {
                            Spacer(Modifier.height(8.dp))
                            Text("מוצגים 20 מתוך ${inv.items.size} פריטים", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            compareResult?.let { diff ->
                item {
                    val risk = diff.optJSONObject("risk") ?: JSONObject()
                    val itemsObj = diff.optJSONObject("items") ?: JSONObject()
                    SectionCard {
                        Text("השוואה לחקירה קודמת", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(7.dp))
                        Text("שינוי סיכון: ${risk.optInt("delta", 0)}")
                        Text("נוספו: ${itemsObj.optJSONArray("added")?.length() ?: 0} • הוסרו: ${itemsObj.optJSONArray("removed")?.length() ?: 0}")
                        Text(if (diff.optBoolean("changed", false)) "נמצאו שינויים" else "לא נמצא שינוי מהותי", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            reportMessage?.let { msg -> item { SectionCard { Text(msg, style = MaterialTheme.typography.bodySmall) } } }
            error?.let { msg -> item { ErrorPanel(msg) } }

            item {
                SectionCard {
                    val status = inv.status.lowercase()
                    if (status == "running") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    actionBusy = true
                                    try { api.pauseInvestigation(inv.id); refreshSelected() }
                                    catch (e: SessionExpiredException) { onSessionExpired() }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                    finally { actionBusy = false }
                                }
                            }, enabled = !actionBusy, modifier = Modifier.weight(1f)) { Text("השהה") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    actionBusy = true
                                    try { api.cancelInvestigation(inv.id); refreshSelected() }
                                    catch (e: SessionExpiredException) { onSessionExpired() }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                    finally { actionBusy = false }
                                }
                            }, enabled = !actionBusy, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("בטל") }
                        }
                    } else if (status == "paused") {
                        Button(onClick = {
                            scope.launch {
                                actionBusy = true
                                try { api.resumeInvestigation(inv.id); refreshSelected() }
                                catch (e: SessionExpiredException) { onSessionExpired() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message }
                                finally { actionBusy = false }
                            }
                        }, enabled = !actionBusy, modifier = Modifier.fillMaxWidth()) { Text("המשך חקירה") }
                    }

                    if (status == "completed") {
                        Spacer(Modifier.height(if (status == "running") 8.dp else 0.dp))
                        Button(onClick = {
                            scope.launch {
                                actionBusy = true
                                try {
                                    val signed = api.signInvestigationReport(inv.id)
                                    val reportId = signed.optString("report_id")
                                    val verified = api.verifyInvestigationReport(reportId)
                                    reportMessage = if (verified.optBoolean("valid", false)) "דוח החקירה נחתם ואומת בהצלחה" else "הדוח נוצר, אך אימות החתימה נכשל"
                                } catch (e: SessionExpiredException) { onSessionExpired() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message }
                                finally { actionBusy = false }
                            }
                        }, enabled = !actionBusy, modifier = Modifier.fillMaxWidth()) { Text("צור דוח חקירה מאומת") }

                        val previous = investigations.firstOrNull {
                            it.id != inv.id &&
                                it.status == "completed" &&
                                it.seedKind == inv.seedKind &&
                                it.seedValue.equals(inv.seedValue, ignoreCase = true)
                        }
                        if (previous != null) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = {
                                scope.launch {
                                    actionBusy = true
                                    try { compareResult = api.compareInvestigations(previous.id, inv.id) }
                                    catch (e: SessionExpiredException) { onSessionExpired() }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                    finally { actionBusy = false }
                                }
                            }, enabled = !actionBusy, modifier = Modifier.fillMaxWidth()) { Text("השווה לחקירה קודמת") }
                        }
                    }

                    if (status in setOf("completed", "failed", "cancelled")) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            enabled = !actionBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("מחק חקירה") }
                    }
                }
            }
        }
    }

    if (confirmDelete && inv != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("מחיקת חקירה") },
            text = { Text("החקירה, הפריטים והדוחות שלה יימחקו לצמיתות. ניתוחי המקור עצמם אינם נמחקים.") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            actionBusy = true
                            try {
                                api.deleteInvestigation(inv.id)
                                selectedId = null
                                selected = null
                                refreshList()
                            } catch (e: SessionExpiredException) { onSessionExpired() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                            finally { actionBusy = false }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("מחק") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("ביטול") } }
        )
    }
}

@Composable
private fun riskColor(score: Int) = when {
    score >= 80 -> MaterialTheme.colorScheme.error
    score >= 50 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

private fun phaseLabel(phase: String): String = when (phase.lowercase()) {
    "planning" -> "מתכנן חקירה"
    "scanning" -> "סורק ומקשר"
    "correlating" -> "מקשר ראיות"
    "completed" -> "הושלם"
    "paused" -> "מושהה"
    "failed" -> "נכשל"
    "cancelled" -> "בוטל"
    else -> phase
}

private fun entityLabel(type: String): String = when (type.lowercase()) {
    "job" -> "ניתוח מקור"
    "domain" -> "דומיין"
    "url" -> "URL"
    "ip" -> "IP"
    "certificate" -> "תעודה"
    "component" -> "רכיב"
    else -> type
}
