package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
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
fun IntelligenceSourcesCard(
    baseUrl: String,
    investigationId: String,
    onSessionExpired: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var payload by remember(investigationId) { mutableStateOf<JSONObject?>(null) }
    var catalog by remember(investigationId) { mutableStateOf<JSONObject?>(null) }
    var sourceHealth by remember(investigationId) { mutableStateOf<JSONObject?>(null) }
    var memory by remember(investigationId) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(investigationId) { mutableStateOf(false) }
    var refreshing by remember(investigationId) { mutableStateOf(false) }
    var privacyMode by remember(investigationId) { mutableStateOf("passive_external") }
    var error by remember(investigationId) { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        try {
            catalog = api.intelligenceSourceCatalog()
            sourceHealth = runCatching { api.intelligenceSourceHealth() }.getOrNull()
            payload = api.investigationSources(investigationId)
            val investigation = runCatching { api.investigation(investigationId) }.getOrNull()
            val memoryEntity = investigation?.let { inv ->
                val supported = setOf("domain", "ip", "asn", "hash")
                val item = inv.items.firstOrNull { it.entityType.lowercase() in supported }
                if (item != null) {
                    item.entityType.lowercase() to item.value
                } else if (inv.seedKind.lowercase() in supported) {
                    inv.seedKind.lowercase() to inv.seedValue
                } else if (inv.seedKind.equals("url", true)) {
                    val host = runCatching { java.net.URI(inv.seedValue).host }.getOrNull()
                    host?.takeIf { it.isNotBlank() }?.let { "domain" to it }
                } else null
            }
            memory = memoryEntity?.let { entity ->
                runCatching {
                    api.intelligenceMemory(
                        entity.first,
                        entity.second,
                        excludeInvestigationId = investigationId
                    )
                }.getOrNull()
            }
            val configuredDefault = catalog?.optString("default_privacy_mode")
            if (!configuredDefault.isNullOrBlank()) privacyMode = configuredDefault
            error = null
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "לא ניתן לטעון מקורות מודיעין"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(investigationId) { reload() }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("Intelligence Sources Federation", fontWeight = FontWeight.Bold)
                Text(
                    "מקורות חיצוניים פסיביים, provenance, היסטוריה ו־consensus בלי לחשוף מפתחות לטלפון.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { scope.launch { reload() } }, enabled = !loading && !refreshing) {
                Icon(Icons.Rounded.Refresh, contentDescription = "רענון")
            }
        }

        if (loading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        payload?.let { result ->
            val observations = result.optJSONArray("observations") ?: JSONArray()
            val consensus = result.optJSONObject("consensus") ?: JSONObject()
            val sourceCount = result.optInt("source_count", 0)
            val configuredSources = catalog?.optJSONArray("sources") ?: JSONArray()
            var configuredCount = 0
            for (i in 0 until configuredSources.length()) {
                if (configuredSources.optJSONObject(i)?.optBoolean("configured", false) == true) configuredCount++
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("מקורות", sourceCount.toString(), Modifier.weight(1f))
                MetricCard("מוגדרים", configuredCount.toString(), Modifier.weight(1f))
                MetricCard("תצפיות", observations.length().toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Risk signal", "${consensus.optDouble("score", 0.0).toInt()}/100", Modifier.weight(1f))
                MetricCard("Confidence", "${(consensus.optDouble("confidence", 0.0) * 100).toInt()}%", Modifier.weight(1f))
            }

            val healthRows = sourceHealth?.optJSONArray("sources") ?: JSONArray()
            var openCircuits = 0
            for (i in 0 until healthRows.length()) {
                if (healthRows.optJSONObject(i)?.optString("state") == "open") openCircuits++
            }
            val priorMatches = memory?.optInt("match_count", 0) ?: 0
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("זיכרון קודם", priorMatches.toString(), Modifier.weight(1f))
                MetricCard("מקורות מושהים", openCircuits.toString(), Modifier.weight(1f))
            }
            if (priorMatches > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "הישות הופיעה בחקירות קודמות שלך. Ghost Eye משתמש בזה כ־context בלבד ולא כהוכחה לקשר חדש.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Text("פרטיות", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = privacyMode == "local_only",
                    onClick = { privacyMode = "local_only" },
                    label = { Text("Local only") }
                )
                FilterChip(
                    selected = privacyMode == "passive_external",
                    onClick = { privacyMode = "passive_external" },
                    label = { Text("Passive") }
                )
            }
            Text(
                if (privacyMode == "local_only") "לא נשלח IOC לשום ספק חיצוני." else "מתבצעים passive lookups בלבד; אין העלאת קבצים או submission לספקים.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    scope.launch {
                        refreshing = true
                        try {
                            api.refreshInvestigationSources(investigationId, privacyMode)
                            reload()
                        } catch (e: SessionExpiredException) {
                            onSessionExpired()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "רענון מקורות המודיעין נכשל"
                        } finally {
                            refreshing = false
                        }
                    }
                },
                enabled = !refreshing && !loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (privacyMode == "local_only") "טען מידע מקומי" else "רענן מקורות פסיביים")
            }

            if (observations.length() > 0) {
                Spacer(Modifier.height(14.dp))
                Text("מקורות אחרונים", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                for (i in 0 until minOf(observations.length(), 8)) {
                    val row = observations.optJSONObject(i) ?: continue
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(row.optString("source_id", "source"), fontWeight = FontWeight.SemiBold)
                            Text(
                                row.optString("summary", "תצפית מודיעינית"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            "${(row.optDouble("confidence", 0.0) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun TargetIntelligenceSourcesCard(
    baseUrl: String,
    target: String,
    onSessionExpired: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var result by remember(target) { mutableStateOf<JSONObject?>(null) }
    var busy by remember(target) { mutableStateOf(false) }
    var privacyMode by remember(target) { mutableStateOf("passive_external") }
    var error by remember(target) { mutableStateOf<String?>(null) }

    fun normalizedTarget(): Pair<String, String>? {
        val raw = target.trim()
        if (raw.isBlank()) return null
        if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            val host = runCatching { java.net.URI(raw).host }.getOrNull()?.trim()?.lowercase()
            return host?.takeIf { it.isNotBlank() }?.let { "domain" to it }
        }
        val looksIpv4 = raw.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$"))
        val looksIpv6 = raw.contains(':') && raw.matches(Regex("^[0-9a-fA-F:]+$"))
        return if (looksIpv4 || looksIpv6) {
            "ip" to raw
        } else {
            "domain" to raw.trimEnd('.').lowercase()
        }
    }

    SectionCard {
        Text("מודיעין ממקורות נוספים", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "RDAP/RIPEstat ומקורות נוספים שהוגדרו בשרת. Passive בלבד — ללא העלאת קבצים לספקים.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = privacyMode == "local_only", onClick = { privacyMode = "local_only" }, label = { Text("Local only") })
            FilterChip(selected = privacyMode == "passive_external", onClick = { privacyMode = "passive_external" }, label = { Text("Passive") })
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val entity = normalizedTarget() ?: return@OutlinedButton
                scope.launch {
                    busy = true
                    try {
                        result = api.intelligenceSourceLookup(entity.first, entity.second, privacyMode)
                        error = null
                    } catch (e: SessionExpiredException) {
                        onSessionExpired()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "בדיקת המקורות נכשלה"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && normalizedTarget() != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text("בדוק מקורות מודיעין")
        }
        result?.let { payload ->
            val observations = payload.optJSONArray("observations") ?: JSONArray()
            val consensus = payload.optJSONObject("consensus") ?: JSONObject()
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("תצפיות", observations.length().toString(), Modifier.weight(1f))
                MetricCard("Signal", "${consensus.optDouble("score", 0.0).toInt()}/100", Modifier.weight(1f))
                MetricCard("Confidence", "${(consensus.optDouble("confidence", 0.0) * 100).toInt()}%", Modifier.weight(1f))
            }
            for (i in 0 until minOf(observations.length(), 5)) {
                val row = observations.optJSONObject(i) ?: continue
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(row.optString("source_id", "source"), fontWeight = FontWeight.SemiBold)
                Text(
                    row.optString("summary", "תצפית מודיעינית"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
