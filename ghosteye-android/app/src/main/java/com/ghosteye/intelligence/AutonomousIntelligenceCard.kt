package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CompareArrows
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun AutonomousIntelligenceCard(
    baseUrl: String,
    investigationId: String,
    status: String,
    onSessionExpired: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var intelligence by remember(investigationId) { mutableStateOf<JSONObject?>(null) }
    var snapshots by remember(investigationId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember(investigationId) { mutableStateOf(false) }
    var busy by remember(investigationId) { mutableStateOf(false) }
    var query by remember(investigationId) { mutableStateOf("") }
    var message by remember(investigationId) { mutableStateOf<String?>(null) }
    var error by remember(investigationId) { mutableStateOf<String?>(null) }
    var snapshotDiff by remember(investigationId) { mutableStateOf<JSONObject?>(null) }
    var pendingDeleteSnapshot by remember(investigationId) { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        if (status.lowercase() == "running") return
        loading = true
        try {
            intelligence = api.investigationIntelligence(investigationId)
            snapshots = api.investigationSnapshots(investigationId)
            error = null
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "לא ניתן לטעון מודיעין מאוחד"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(investigationId, status) { refresh() }

    if (status.lowercase() == "running") {
        SectionCard {
            Text("מודיעין מאוחד", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Ghost Eye מאחד את הממצאים, הראיות והקשרים בזמן שהחקירה מתקדמת. התמונה המלאה תופיע בסיום.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("מודיעין מאוחד", fontWeight = FontWeight.Bold)
                Text(
                    "ממצאים מתואמים, ראיות, ישויות, כיסוי וצעדים מומלצים במקום dump גולמי.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { scope.launch { refresh() } }, enabled = !loading && !busy) {
                Icon(Icons.Rounded.Refresh, contentDescription = "רענון")
            }
        }

        if (loading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        intelligence?.let { brief ->
            val risk = brief.optJSONObject("risk") ?: JSONObject()
            val coverage = brief.optJSONObject("coverage") ?: JSONObject()
            val findings = jsonObjectList(brief.optJSONArray("findings"))
            val entities = jsonObjectList(brief.optJSONArray("entities"))
            val evidence = jsonObjectList(brief.optJSONArray("evidence"))
            val aiObservations = jsonObjectList(brief.optJSONArray("ai_observations"))
            val steps = jsonObjectList(brief.optJSONArray("recommended_next_steps"))
            val confidence = (brief.optDouble("confidence", 0.0) * 100).toInt().coerceIn(0, 100)
            val score = risk.optInt("score", 0).coerceIn(0, 100)
            val summary = brief.optString("summary_he").ifBlank { brief.optString("summary") }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("סיכון", "$score/100", Modifier.weight(1f))
                MetricCard("ביטחון", "$confidence%", Modifier.weight(1f))
                MetricCard("ממצאים", findings.size.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("ישויות", entities.size.toString(), Modifier.weight(1f))
                MetricCard("ראיות", evidence.size.toString(), Modifier.weight(1f))
                MetricCard("כיסוי", "${coverage.optInt("items_completed", 0)}/${coverage.optInt("items_total", 0)}", Modifier.weight(1f))
            }

            if (summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium)
            }

            if (aiObservations.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("AI מבוסס ראיות", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                aiObservations.take(4).forEachIndexed { index, claim ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(claim.optString("text", "מסקנה מבוססת ראיות"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "מגובה ב-${claim.optJSONArray("evidence_ids")?.length() ?: 0} ראיות",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (findings.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(120) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    label = { Text("חיפוש בממצאים") }
                )
                val normalized = query.trim().lowercase()
                val filtered = findings.filter { finding ->
                    if (normalized.isBlank()) true else {
                        listOf(
                            finding.optString("title"),
                            finding.optString("description"),
                            finding.optString("target"),
                            finding.optString("severity")
                        ).any { it.lowercase().contains(normalized) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("ממצאים חשובים", fontWeight = FontWeight.Bold)
                filtered.take(8).forEachIndexed { index, finding ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 9.dp))
                    val severity = finding.optString("severity", "unknown")
                    Row(verticalAlignment = Alignment.Top) {
                        AssistChip(onClick = {}, label = { Text(severityLabel110(severity)) })
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(finding.optString("title", "ממצא"), fontWeight = FontWeight.SemiBold)
                            finding.optString("description").takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(3.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                            finding.optString("target").takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(3.dp))
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                if (filtered.size > 8) {
                    Spacer(Modifier.height(7.dp))
                    Text("מוצגים 8 מתוך ${filtered.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("מה מומלץ לעשות עכשיו", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                steps.take(5).forEachIndexed { index, step ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(step.optString("title_he").ifBlank { step.optString("title") }, fontWeight = FontWeight.SemiBold)
                    step.optString("detail_he").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        message?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        api.createInvestigationSnapshot(investigationId)
                        snapshots = api.investigationSnapshots(investigationId)
                        snapshotDiff = null
                        message = "Snapshot נשמר"
                        error = null
                    } catch (e: SessionExpiredException) {
                        onSessionExpired()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "שמירת Snapshot נכשלה"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && intelligence != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Rounded.Save, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("שמור Snapshot")
        }

        if (snapshots.size >= 2) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            snapshotDiff = api.compareInvestigationSnapshots(snapshots[1].optString("id"), snapshots[0].optString("id"))
                            error = null
                        } catch (e: SessionExpiredException) {
                            onSessionExpired()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "השוואת Snapshots נכשלה"
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.CompareArrows, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("השווה שני Snapshots אחרונים")
            }
        }

        snapshotDiff?.let { diff ->
            val risk = diff.optJSONObject("risk") ?: JSONObject()
            val findings = diff.optJSONObject("findings") ?: JSONObject()
            Spacer(Modifier.height(10.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("שינוי מאז Snapshot קודם", fontWeight = FontWeight.Bold)
                    Text("סיכון: ${risk.optInt("from", 0)} → ${risk.optInt("to", 0)} (${risk.optInt("delta", 0)})")
                    Text("ממצאים חדשים: ${findings.optJSONArray("added")?.length() ?: 0} • הוסרו: ${findings.optJSONArray("removed")?.length() ?: 0}")
                    Text(if (diff.optBoolean("changed", false)) "נמצא שינוי" else "אין שינוי מהותי", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (snapshots.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text("Snapshots", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(snapshots.take(8), key = { it.optString("id") }) { snapshot ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(snapshot.optString("label").ifBlank { "Snapshot" }, fontWeight = FontWeight.SemiBold)
                                Text("סיכון ${snapshot.optInt("risk_score", 0)}/100", style = MaterialTheme.typography.labelSmall)
                            }
                            IconButton(onClick = { pendingDeleteSnapshot = snapshot.optString("id") }, enabled = !busy) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "מחק Snapshot", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDeleteSnapshot?.let { snapshotId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSnapshot = null },
            title = { Text("מחיקת Snapshot") },
            text = { Text("ה־Snapshot יימחק לצמיתות. החקירה עצמה לא תימחק.") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteSnapshot = null
                        scope.launch {
                            busy = true
                            try {
                                api.deleteInvestigationSnapshot(snapshotId)
                                snapshots = api.investigationSnapshots(investigationId)
                                snapshotDiff = null
                                message = "Snapshot נמחק"
                            } catch (e: SessionExpiredException) {
                                onSessionExpired()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "מחיקת Snapshot נכשלה"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("מחק") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteSnapshot = null }) { Text("ביטול") } }
        )
    }
}

private fun jsonObjectList(array: JSONArray?): List<JSONObject> = buildList {
    val source = array ?: return@buildList
    for (i in 0 until source.length()) source.optJSONObject(i)?.let(::add)
}

private fun severityLabel110(value: String): String = when (value.lowercase()) {
    "critical" -> "קריטי"
    "high" -> "גבוה"
    "medium" -> "בינוני"
    "low" -> "נמוך"
    "info" -> "מידע"
    else -> "לא ידוע"
}
