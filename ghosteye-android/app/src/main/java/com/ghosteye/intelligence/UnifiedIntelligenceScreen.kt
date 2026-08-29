package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.objectRows(): List<Pair<String, JSONObject>> = buildList {
    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        optJSONObject(key)?.let { add(key to it) }
    }
}

@Composable
fun UnifiedIntelligenceScreen(
    baseUrl: String,
    modifier: Modifier = Modifier,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var capabilities by remember { mutableStateOf<JSONObject?>(null) }
    var providers by remember { mutableStateOf<JSONObject?>(null) }
    var connectors by remember { mutableStateOf<JSONObject?>(null) }
    var sandbox by remember { mutableStateOf<JSONObject?>(null) }
    var certification by remember { mutableStateOf<JSONObject?>(null) }
    var playbooks by remember { mutableStateOf<JSONObject?>(null) }
    var clusters by remember { mutableStateOf<JSONObject?>(null) }
    var watchlists by remember { mutableStateOf<JSONObject?>(null) }
    var alerts by remember { mutableStateOf<JSONObject?>(null) }
    var freeOsint by remember { mutableStateOf<JSONObject?>(null) }
    var threatFeeds by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        if (loading) return
        scope.launch {
            loading = true
            try {
                // Keep calls independent on purpose: every endpoint has its own server-side
                // bounds/caching and no API keys are ever returned to the Android client.
                capabilities = api.unifiedCapabilities()
                providers = api.providerSla()
                connectors = api.externalConnectors()
                sandbox = api.sandboxV3Status()
                certification = api.certificationMatrix()
                playbooks = api.intelligencePlaybooks()
                clusters = api.infrastructureClusters(500)
                watchlists = api.intelligenceWatchlistsV14()
                alerts = api.intelligenceAlertsV14(50)
                freeOsint = api.freeOsintRegistry()
                threatFeeds = api.threatFeedMeshStatus()
                error = null
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "טעינת מרכז המודיעין נכשלה"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(baseUrl) { refresh() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard {
                Text("Unified Intelligence 14", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("מצב 80 היכולות, מקורות, sandbox, playbooks ו־production gates — בלי לחשוף מפתחות API לטלפון.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text(if (loading) "טוען…" else "רענן מצב מערכת")
                }
                if (loading) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }

        freeOsint?.let { registry ->
            val rows = registry.optJSONArray("sources") ?: JSONArray()
            val activeRows = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter { it.optBoolean("active", false) }
            item {
                SectionCard {
                    Text("Free OSINT Mesh", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("קטלוג", registry.optInt("total_sources", rows.length()).toString(), Modifier.weight(1f))
                        MetricCard("Adapters פעילים", registry.optInt("active_adapters", activeRows.size).toString(), Modifier.weight(1f))
                        MetricCard("בהרחבה", registry.optInt("catalog_only", 0).toString(), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    activeRows.take(12).forEach { row ->
                        Text("• ${row.optString("name", row.optString("id"))} — ${row.optString("access", "free")}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (activeRows.size > 12) Text("+${activeRows.size - 12} מקורות פעילים נוספים", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        threatFeeds?.let { mesh ->
            val feeds = mesh.optJSONArray("feeds") ?: JSONArray()
            val healthy = (0 until feeds.length()).mapNotNull { feeds.optJSONObject(it) }.count { it.optString("status") == "ok" }
            item {
                SectionCard {
                    Text("Background Threat Feed Harvester", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("Feeds", mesh.optInt("feed_count", feeds.length()).toString(), Modifier.weight(1f))
                        MetricCard("תקינים", healthy.toString(), Modifier.weight(1f))
                        MetricCard("IOC פעילים", mesh.optInt("active_indicator_count", 0).toString(), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    (0 until feeds.length()).mapNotNull { feeds.optJSONObject(it) }.forEach { row ->
                        val status = row.optString("status", "never-synced")
                        val count = row.optInt("records_active", 0)
                        Text("• ${row.optString("name", row.optString("id"))} — $status • $count IOC", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("ה־feeds מסונכרנים בשרת ונבדקים מקומית בזמן חקירה; הטלפון לא מוריד feeds ולא נחשף למפתחות.", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        capabilities?.let { payload ->
            val arr = payload.optJSONArray("features") ?: JSONArray()
            val active = (0 until arr.length()).count { arr.optJSONObject(it)?.optBoolean("configured", false) == true }
            val adaptersUnavailable = (0 until arr.length()).count {
                val row = arr.optJSONObject(it)
                row != null && row.optString("implementation") == "adapter" && !row.optBoolean("available", false)
            }
            item {
                SectionCard {
                    Text("Capabilities", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("סה״כ", payload.optInt("feature_count", arr.length()).toString(), Modifier.weight(1f))
                        MetricCard("פעיל", active.toString(), Modifier.weight(1f))
                        MetricCard("Adapters חסרים", adaptersUnavailable.toString(), Modifier.weight(1f))
                    }
                }
            }
            items((0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.take(80), key = { it.optInt("id") }) { feature ->
                SectionCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${feature.optInt("id")}. ${feature.optString("name")}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(if (feature.optBoolean("configured", false)) "פעיל" else if (feature.optBoolean("available", false)) "זמין" else "לא זמין", style = MaterialTheme.typography.labelSmall)
                    }
                    feature.optString("detail").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }


        item {
            val wl = watchlists?.optJSONArray("watchlists")?.length() ?: 0
            val al = alerts?.optJSONArray("alerts") ?: JSONArray()
            SectionCard {
                Text("Watchlists & Alerts", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard("מעקבים", wl.toString(), Modifier.weight(1f))
                    MetricCard("התראות", al.length().toString(), Modifier.weight(1f))
                }
                for (i in 0 until minOf(al.length(), 5)) {
                    val row = al.optJSONObject(i) ?: continue
                    Spacer(Modifier.height(7.dp))
                    Text("• ${row.optString("title", row.optString("alert_type", "alert"))}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        sandbox?.let { s ->
            item {
                SectionCard {
                    Text("Sandbox v3", fontWeight = FontWeight.Bold)
                    Text("Backend: ${s.optString("configured_backend", "unknown")}")
                    Text("Isolation: ${s.optString("effective_isolation", "unknown")}")
                    Text("Network: ${s.optString("network_default", "none")}")
                    Text("microVM: ${if (s.optBoolean("microvm_configured", false)) "פעיל" else "לא נטען"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        certification?.let { c ->
            item {
                SectionCard {
                    Text("E2E Certification", fontWeight = FontWeight.Bold)
                    val certified = c.optBoolean("certified", false)
                    Text(if (certified) "CERTIFIED" else "לא מסומן כ־Certified עד שכל gates מוכחים בפועל", color = if (certified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    val gates = c.optJSONArray("gates") ?: JSONArray()
                    Text("Gates: ${gates.length()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        clusters?.let { c ->
            item {
                SectionCard {
                    Text("Infrastructure Clusters", fontWeight = FontWeight.Bold)
                    Text("Clusters: ${c.optJSONArray("clusters")?.length() ?: 0}")
                }
            }
        }

        providers?.let { p ->
            val rows = p.optJSONArray("providers") ?: JSONArray()
            item { Text("Provider SLA", fontWeight = FontWeight.Bold) }
            items((0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.take(20), key = { it.optString("source_id", it.optString("id")) }) { row ->
                SectionCard {
                    Text(row.optString("name", row.optString("source_id", "provider")), fontWeight = FontWeight.SemiBold)
                    val avgLatency = row.optDouble("historical_avg_latency_ms", row.optDouble("avg_latency_ms", 0.0))
                    val successRate = if (row.has("historical_success_rate") && !row.isNull("historical_success_rate")) " • ${(row.optDouble("historical_success_rate") * 100).toInt()}% success" else ""
                    Text("Health: ${row.optString("health_state", row.optString("state", "unknown"))} • avg ${avgLatency.toInt()} ms$successRate", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        connectors?.let { c ->
            item {
                SectionCard {
                    Text("External Connectors", fontWeight = FontWeight.Bold)
                    c.objectRows().filter { it.first != "schema" }.forEach { (name, row) ->
                        Text("$name: ${if (row.optBoolean("configured", false)) "configured" else "disabled"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        playbooks?.optJSONObject("playbooks")?.let { p ->
            item {
                SectionCard {
                    Text("Investigation Playbooks", fontWeight = FontWeight.Bold)
                    val iterator = p.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        val row = p.optJSONObject(key) ?: continue
                        Text("• ${row.optString("name", key)} — ${row.optJSONArray("steps")?.length() ?: 0} steps", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
