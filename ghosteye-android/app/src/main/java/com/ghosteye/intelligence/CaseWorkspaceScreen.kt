package com.ghosteye.intelligence

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun CaseWorkspaceScreen(
    baseUrl: String,
    modifier: Modifier = Modifier,
    onSessionExpired: () -> Unit,
    onOpenInvestigation: (String) -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()

    var cases by remember { mutableStateOf<List<CaseWorkspaceSummary>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var correlations by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var includeArchived by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var noteText by remember { mutableStateOf("") }

    suspend fun loadList() {
        loading = true
        try {
            cases = api.caseWorkspaces(includeArchived)
            error = null
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "לא ניתן לטעון Cases"
        } finally {
            loading = false
        }
    }

    suspend fun loadDetail(id: String) {
        loading = true
        try {
            detail = api.caseWorkspace(id)
            correlations = api.caseCorrelations(id)
            error = null
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "לא ניתן לטעון את ה־Case"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(includeArchived) { loadList() }
    LaunchedEffect(selectedId) { selectedId?.let { loadDetail(it) } }

    if (selectedId == null) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PageTitle("Cases", "מרכז עבודה לחקירות, ראיות וקשרים בין יעדים")
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it.take(300) },
                        label = { Text("חיפוש Domain / IP / Hash / Entity") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (search.trim().length >= 2) {
                                scope.launch {
                                    busy = true
                                    try {
                                        val result = api.searchCaseEntities(search)
                                        searchResults = jsonObjects(result.optJSONArray("results"))
                                    } catch (e: SessionExpiredException) { onSessionExpired() }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                    finally { busy = false }
                                }
                            }
                        },
                        enabled = !busy && search.trim().length >= 2
                    ) { Text("חפש") }
                }
            }
            if (searchResults.isNotEmpty()) {
                item {
                    SectionCard {
                        Text("תוצאות חיפוש גלובליות", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        searchResults.take(20).forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider(Modifier.padding(vertical = 7.dp))
                            Text(row.optString("value"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${row.optString("entity_type")} • ${row.optString("case_title", "Case")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val cid = row.optString("case_id")
                            if (cid.isNotBlank()) {
                                TextButton(onClick = { selectedId = cid }) { Text("פתח Case") }
                            }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = includeArchived, onCheckedChange = { includeArchived = it })
                    Spacer(Modifier.width(8.dp))
                    Text("הצג גם ארכיון")
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { msg -> item { ErrorPanel(msg) { scope.launch { loadList() } } } }
            if (!loading && cases.isEmpty()) {
                item { SectionCard { Text("אין Cases להצגה כרגע.") } }
            }
            items(cases, key = { it.id }) { c ->
                Card(
                    onClick = { selectedId = c.id },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${priorityLabel(c.priority)} • ${c.investigationCount} חקירות • ${c.entityCount} ישויות",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            c.latestRiskScore?.let { RiskBadge(it) }
                        }
                        if (c.tags.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(c.tags.joinToString("  •  ").take(180), style = MaterialTheme.typography.labelSmall)
                        }
                        if (c.archived) {
                            Spacer(Modifier.height(6.dp))
                            Text("בארכיון", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        return
    }

    val d = detail
    if (d == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator() else Text(error ?: "לא נמצא מידע")
        }
        return
    }

    val caseId = d.optString("id")
    val investigations = jsonObjects(d.optJSONArray("investigations"))
    val notes = jsonObjects(d.optJSONArray("notes_timeline"))
    val entities = jsonObjects(d.optJSONArray("entities"))
    val related = jsonObjects(correlations?.optJSONArray("related_cases"))
    val latest = d.optJSONObject("latest_investigation")

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = { selectedId = null; detail = null; correlations = null }) { Text("‹ חזרה ל־Cases") }
            PageTitle(d.optString("title", "Case"), "Workspace מאוחד")
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("מצב: ${statusLabel(d.optString("status", "active"))}", fontWeight = FontWeight.SemiBold)
                        Text("עדיפות: ${priorityLabel(d.optString("priority", "normal"))}", style = MaterialTheme.typography.bodySmall)
                        Text("${d.optInt("investigation_count", 0)} חקירות • ${d.optInt("entity_count", 0)} ישויות", style = MaterialTheme.typography.bodySmall)
                    }
                    latest?.let { RiskBadge(it.optInt("risk_score", 0)) }
                }
                Spacer(Modifier.height(12.dp))
                Text("עדיפות", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("low" to "נמוכה", "normal" to "רגילה", "high" to "גבוהה", "critical" to "קריטית").forEach { (value, label) ->
                        FilterChip(
                            selected = d.optString("priority", "normal") == value,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    try { api.updateCaseWorkspace(caseId, priority = value); loadDetail(caseId); loadList() }
                                    catch (e: SessionExpiredException) { onSessionExpired() }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message }
                                    finally { busy = false }
                                }
                            },
                            label = { Text(label) },
                            enabled = !busy
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = d.optBoolean("watch_enabled", false),
                        onCheckedChange = { checked ->
                            scope.launch {
                                busy = true
                                try { api.updateCaseWorkspace(caseId, watchEnabled = checked, watchIntervalHours = d.optInt("watch_interval_hours", 24)); loadDetail(caseId) }
                                catch (e: SessionExpiredException) { onSessionExpired() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message }
                                finally { busy = false }
                            }
                        },
                        enabled = !busy
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("מעקב אוטומטי ליעדי Domain/IP")
                }
            }
        }
        item {
            SectionCard {
                Text("פעולות חקירה", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val result = api.smartReinvestigate(caseId, force = false)
                                val inv = result.optJSONObject("investigation")
                                val id = inv?.optString("id").orEmpty()
                                if (id.isNotBlank()) onOpenInvestigation(id) else loadDetail(caseId)
                            } catch (e: SessionExpiredException) { onSessionExpired() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("רענן חקירה חכם") }
                if (latest?.optString("status") == "paused") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val result = api.resumeLatestCaseInvestigation(caseId)
                                    val id = result.optJSONObject("investigation")?.optString("id").orEmpty()
                                    if (id.isNotBlank()) onOpenInvestigation(id) else loadDetail(caseId)
                                } catch (e: SessionExpiredException) { onSessionExpired() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { error = e.message }
                                finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    ) { Text("המשך חקירה") }
                }
                Spacer(Modifier.height(8.dp))
                val archived = d.optBoolean("archived", false)
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                if (archived) api.restoreCase(caseId) else api.archiveCase(caseId)
                                loadDetail(caseId); loadList()
                            } catch (e: SessionExpiredException) { onSessionExpired() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy
                ) { Text(if (archived) "החזר מהארכיון" else "העבר לארכיון") }
            }
        }
        item {
            SectionCard {
                Text("הערות", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it.take(10000) },
                    label = { Text("הוסף הערה ל־Case") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val body = noteText.trim()
                        if (body.isNotBlank()) scope.launch {
                            busy = true
                            try { api.addCaseNote(caseId, body); noteText = ""; loadDetail(caseId) }
                            catch (e: SessionExpiredException) { onSessionExpired() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message }
                            finally { busy = false }
                        }
                    },
                    enabled = !busy && noteText.isNotBlank()
                ) { Text("שמור הערה") }
                notes.take(20).forEachIndexed { index, n ->
                    if (index >= 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(n.optString("body"), style = MaterialTheme.typography.bodyMedium)
                    Text(n.optString("created_at"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (related.isNotEmpty()) {
            item {
                SectionCard {
                    Text("קשרים ל־Cases אחרים", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    related.take(20).forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 7.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.optString("title", "Case"), fontWeight = FontWeight.SemiBold)
                                Text("${row.optInt("shared_entity_count", 0)} ישויות משותפות", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { selectedId = row.optString("case_id") }) { Text("פתח") }
                        }
                    }
                }
            }
        }
        if (entities.isNotEmpty()) {
            item {
                SectionCard {
                    Text("Entity Memory", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    entities.take(30).forEachIndexed { index, e ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Text(e.optString("value"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text("${e.optString("entity_type")} • ביטחון ${e.optInt("confidence", 0)}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (investigations.isNotEmpty()) {
            item {
                SectionCard {
                    Text("Timeline חקירות", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    investigations.take(30).forEachIndexed { index, inv ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 7.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                inv.optString("id").takeIf { it.isNotBlank() }?.let(onOpenInvestigation)
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(inv.optString("title", "חקירה"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                Text("${inv.optString("status")} • סיכון ${inv.optInt("risk_score", 0)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("פתח", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        error?.let { msg -> item { ErrorPanel(msg) { scope.launch { loadDetail(caseId) } } } }
    }
}

@Composable
private fun RiskBadge(score: Int) {
    val container = when {
        score >= 80 -> MaterialTheme.colorScheme.errorContainer
        score >= 50 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Surface(shape = RoundedCornerShape(999.dp), color = container) {
        Text("$score", Modifier.padding(horizontal = 11.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
    }
}

private fun jsonObjects(arr: JSONArray?): List<JSONObject> = buildList {
    if (arr == null) return@buildList
    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(::add)
}

private fun priorityLabel(value: String): String = when (value.lowercase()) {
    "critical" -> "קריטית"
    "high" -> "גבוהה"
    "low" -> "נמוכה"
    else -> "רגילה"
}

private fun statusLabel(value: String): String = when (value.lowercase()) {
    "review" -> "בבדיקה"
    "monitoring" -> "במעקב"
    "closed" -> "סגור"
    else -> "פעיל"
}
