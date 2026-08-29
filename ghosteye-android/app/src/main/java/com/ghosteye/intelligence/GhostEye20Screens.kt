package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.firstObject(vararg keys: String): JSONObject? {
    keys.forEach { key -> optJSONObject(key)?.let { return it } }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FabricInvestigationScreen(baseUrl: String, modifier: Modifier = Modifier, onSessionExpired: () -> Unit) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var entityType by remember { mutableStateOf("domain") }
    var value by remember { mutableStateOf("") }
    var ecosystem by remember { mutableStateOf("pypi") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copilot by remember { mutableStateOf<JSONObject?>(null) }
    var copilotLoading by remember { mutableStateOf(false) }
    val types = listOf("domain", "ip", "url", "hash", "asn", "cve", "package")

    LaunchedEffect(Unit) {
        try { status = api.intelligenceFabricStatus() } catch (_: Exception) {}
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            CommandHero("INTELLIGENCE FABRIC", "חקירה חכמה", "OSINT Mesh → Evidence Fusion → Vulnerability Intelligence → Entity Graph → Risk Snapshot")
        }
        status?.let { s ->
            item {
                val graph = s.firstObject("graph", "knowledge_graph") ?: s
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard("Entities", graph.optInt("entities", graph.optInt("entity_count", 0)).toString(), Modifier.weight(1f))
                    MetricCard("Relations", graph.optInt("relationships", graph.optInt("relationship_count", 0)).toString(), Modifier.weight(1f))
                    MetricCard("Observations", s.optInt("observations", s.optInt("observation_count", 0)).toString(), Modifier.weight(1f))
                }
            }
        }
        item {
            SectionCard {
                Text("Intelligence Fabric 2.0", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    types.take(4).forEachIndexed { index, type ->
                        SegmentedButton(selected = entityType == type, onClick = { entityType = type }, shape = SegmentedButtonDefaults.itemShape(index, 4)) { Text(type) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    types.drop(4).forEachIndexed { index, type ->
                        SegmentedButton(selected = entityType == type, onClick = { entityType = type }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(type) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = value, onValueChange = { value = it.take(512) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Entity") }, leadingIcon = { Icon(Icons.Rounded.Search, null) })
                if (entityType == "package") {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = ecosystem, onValueChange = { ecosystem = it.take(32) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Ecosystem (pypi/npm/maven/go/...)") })
                }
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    if (value.isBlank() || loading) return@Button
                    scope.launch {
                        loading = true; error = null
                        try { result = api.intelligenceFabricV2(entityType, value.trim(), if (entityType == "package") ecosystem else null); status = api.intelligenceFabricStatus() }
                        catch (e: SessionExpiredException) { onSessionExpired() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Investigation failed" }
                        finally { loading = false }
                    }
                }, enabled = value.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "Investigating…" else "Run Intelligence Fabric") }
                if (loading) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }
        result?.let { r ->
            item {
                val fusion = r.firstObject("fusion", "evidence_fusion") ?: JSONObject()
                val graph = r.firstObject("graph", "knowledge_graph") ?: JSONObject()
                SectionCard {
                    Text("Investigation result", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("Risk", r.optInt("risk_score", fusion.optInt("risk_score", 0)).toString(), Modifier.weight(1f))
                        MetricCard("Quality", fusion.optInt("quality_score", 0).toString(), Modifier.weight(1f))
                        MetricCard("Sources", fusion.optInt("successful_sources", r.optInt("source_count", 0)).toString(), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Investigation: ${r.optString("investigation_id", "saved")}", style = MaterialTheme.typography.bodySmall)
                    Text("Graph entities: ${graph.optInt("entities", graph.optInt("entity_count", 0))} • edges: ${graph.optInt("relationships", graph.optInt("edge_count", 0))}", style = MaterialTheme.typography.bodySmall)
                    r.optString("safety").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    val investigationId = r.optString("investigation_id")
                    if (investigationId.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    copilotLoading = true
                                    try { copilot = api.investigationCopilotV14(investigationId) }
                                    catch (e: SessionExpiredException) { onSessionExpired() }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = e.message ?: "Copilot unavailable" }
                                    finally { copilotLoading = false }
                                }
                            },
                            enabled = !copilotLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (copilotLoading) "Planning…" else "Investigation Copilot") }
                    }
                }
            }
            copilot?.let { c ->
                item {
                    SectionCard {
                        Text("Copilot plan", fontWeight = FontWeight.Bold)
                        val actions = c.optJSONArray("actions") ?: JSONArray()
                        Text("Suggested next actions: ${actions.length()}", style = MaterialTheme.typography.bodySmall)
                        for (i in 0 until minOf(actions.length(), 8)) {
                            val a = actions.optJSONObject(i)
                            Text("• ${a?.optString("title", a?.optString("action", a?.toString() ?: "action")) ?: actions.optString(i)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Shell access: ${c.optBoolean("shell_access", false)} • Network policy required: ${c.optBoolean("policy_required_for_network", true)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun VulnerabilityCenterScreen(baseUrl: String, modifier: Modifier = Modifier, onSessionExpired: () -> Unit) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("cve") }
    var query by remember { mutableStateOf("") }
    var ecosystem by remember { mutableStateOf("pypi") }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var sources by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { try { sources = api.cveSourcesV15() } catch (_: Exception) {} }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { CommandHero("VULNERABILITY CENTER", "מודיעין פגיעויות", "CVE, CVSS, EPSS, KEV, OSV, GitHub Advisories וראיות מקומיות במקום אחד") }
        sources?.let { s -> item { SectionCard { Text("Vulnerability sources", fontWeight = FontWeight.Bold); Text("Configured sources: ${s.optJSONArray("sources")?.length() ?: s.optInt("count", 0)}", style = MaterialTheme.typography.bodySmall) } } }
        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "cve", onClick = { mode = "cve" }, label = { Text("CVE") })
                    FilterChip(selected = mode == "package", onClick = { mode = "package" }, label = { Text("Package") })
                }
                OutlinedTextField(query, { query = it.take(256) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(if (mode == "cve") "CVE-YYYY-NNNN" else "Package name") })
                if (mode == "package") OutlinedTextField(ecosystem, { ecosystem = it.take(32) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Ecosystem") })
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    scope.launch {
                        loading = true; error = null
                        try { result = if (mode == "cve") api.liveVulnerabilityIntelligence(query) else api.packageVulnerabilityIntelligence(query, ecosystem) }
                        catch (e: SessionExpiredException) { onSessionExpired() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Vulnerability lookup failed" }
                        finally { loading = false }
                    }
                }, enabled = query.isNotBlank() && !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "Correlating…" else "Correlate vulnerability intelligence") }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        result?.let { r -> item {
            val summary = r.firstObject("summary", "vulnerability") ?: r
            SectionCard {
                Text(summary.optString("cve_id", summary.optString("id", query)), fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricCard("Priority", summary.optInt("priority_score", r.optInt("priority_score", 0)).toString(), Modifier.weight(1f))
                    MetricCard("CVSS", summary.optDouble("cvss", summary.optDouble("cvss_score", 0.0)).toString(), Modifier.weight(1f))
                    MetricCard("EPSS", "${(summary.optDouble("epss", 0.0) * 100).toInt()}%", Modifier.weight(1f))
                }
                Text("KEV: ${summary.optBoolean("kev", summary.optBoolean("known_exploited", false))}", style = MaterialTheme.typography.bodySmall)
                Text("Corroborating sources: ${r.optInt("source_count", r.optJSONArray("sources")?.length() ?: 0)}", style = MaterialTheme.typography.bodySmall)
            }
        } }
    }
}

@Composable
fun WatchtowerCenterScreen(baseUrl: String, modifier: Modifier = Modifier, onSessionExpired: () -> Unit) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var watches by remember { mutableStateOf(JSONArray()) }
    var alerts by remember { mutableStateOf(JSONArray()) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("domain") }
    var value by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var evaluating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() { scope.launch {
        loading = true
        try {
            val w = api.intelligenceWatchlistsV14(); watches = w.optJSONArray("items") ?: w.optJSONArray("watchlists") ?: JSONArray()
            val a = api.intelligenceAlertsV14(100); alerts = a.optJSONArray("items") ?: a.optJSONArray("alerts") ?: JSONArray()
            status = try { api.watchtowerStatusV20() } catch (_: Exception) { null }
            error = null
        } catch (e: SessionExpiredException) { onSessionExpired() }
        catch (e: Exception) { error = e.message ?: "Watchtower unavailable" }
        finally { loading = false }
    } }

    LaunchedEffect(Unit) {
        refresh()
        while (true) { delay(30_000); refresh() }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { CommandHero("WATCHTOWER 24/7", "מעקב חכם", "Risk Delta, שינויים בגרף והתראות ראיות לפי עדיפות") }
        item {
            val urgent = status?.optInt("urgent_alerts", 0) ?: 0
            val high = status?.optInt("high_alerts", 0) ?: 0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Watches", (status?.optInt("enabled", watches.length()) ?: watches.length()).toString(), Modifier.weight(1f))
                MetricCard("Open", (status?.optInt("open_alerts", alerts.length()) ?: alerts.length()).toString(), Modifier.weight(1f))
                MetricCard("Urgent", urgent.toString(), Modifier.weight(1f))
            }
            if (high > 0) Text("High priority alerts: $high", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { refresh() }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Refresh") }
                Button(onClick = { scope.launch {
                    evaluating = true
                    try { api.evaluateAllWatchlistsV20(100); refresh() }
                    catch (e: SessionExpiredException) { onSessionExpired() }
                    catch (e: Exception) { error = e.message ?: "Evaluation failed" }
                    finally { evaluating = false }
                } }, enabled = !evaluating, modifier = Modifier.weight(1f)) { Text(if (evaluating) "Evaluating…" else "Evaluate now") }
            }
            if (loading || evaluating) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            SectionCard {
                Text("Add entity watch", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("domain","ip","cve","package").forEach { preset ->
                        FilterChip(selected = type == preset, onClick = { type = preset }, label = { Text(preset.uppercase()) })
                    }
                }
                OutlinedTextField(name, { name = it.take(120) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
                OutlinedTextField(type, { type = it.take(32) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Entity type") })
                OutlinedTextField(value, { value = it.take(512) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(if (type == "cve") "CVE-YYYY-NNNN" else if (type == "package") "Package name" else "Value") })
                Button(onClick = { scope.launch {
                    try { api.createEntityWatchlistV14(name.ifBlank { "Watch: $value" }, type, value); name = ""; value = ""; refresh() }
                    catch (e: SessionExpiredException) { onSessionExpired() }
                    catch (e: Exception) { error = e.message ?: "Failed to add watch" }
                } }, enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Add to Watchtower") }
            }
        }
        if (alerts.length() > 0) {
            item { Text("Prioritized alerts", fontWeight = FontWeight.Bold) }
            items((0 until minOf(alerts.length(), 40)).mapNotNull { alerts.optJSONObject(it) }
                .sortedByDescending { it.optInt("priority_score", it.optJSONObject("payload")?.optInt("priority_score", 0) ?: 0) }) { a ->
                val payload = a.optJSONObject("payload") ?: JSONObject()
                val priority = a.optInt("priority_score", payload.optInt("priority_score", 0))
                val severity = a.optString("severity", "info")
                SectionCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(a.optString("title", a.optString("kind", "Alert")), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("$priority", fontWeight = FontWeight.Bold)
                    }
                    Text("${severity.uppercase()} • ${a.optString("priority_band", payload.optString("priority_band", "normal")).uppercase()}", style = MaterialTheme.typography.labelSmall)
                    Text(payload.optString("entity", a.optString("summary", a.optString("message", "Evidence changed"))), style = MaterialTheme.typography.bodySmall)
                    val before = payload.opt("before")
                    val after = payload.opt("after")
                    if (before != null && after != null) Text("Risk/evidence delta: $before → $after", style = MaterialTheme.typography.bodySmall)
                    Text(a.optString("created_at", a.optString("observed_at", "")), style = MaterialTheme.typography.labelSmall)
                    if (a.optString("status", "open") == "open") {
                        OutlinedButton(onClick = { scope.launch {
                            try { api.acknowledgeAlertV20(a.optString("id")); refresh() }
                            catch (e: SessionExpiredException) { onSessionExpired() }
                            catch (e: Exception) { error = e.message ?: "Could not acknowledge alert" }
                        } }, modifier = Modifier.fillMaxWidth()) { Text("Acknowledge") }
                    }
                }
            }
        }
    }
}
