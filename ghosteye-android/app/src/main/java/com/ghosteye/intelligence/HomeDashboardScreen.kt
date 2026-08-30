package com.ghosteye.intelligence

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class HomeState(
    val fabric: JSONObject = JSONObject(),
    val watchtower: JSONObject = JSONObject(),
    val providerEnv: JSONObject = JSONObject(),
    val registry: JSONObject = JSONObject(),
    val alerts: JSONArray = JSONArray(),
    val investigations: List<InvestigationSummary> = emptyList(),
    val server: JSONObject = JSONObject(),
    val inventory: JSONObject = JSONObject(),
    val inventoryChanges: JSONObject = JSONObject()
)

@Composable
fun HomeDashboardScreen(
    baseUrl: String,
    modifier: Modifier = Modifier,
    onSessionExpired: () -> Unit,
    onInvestigate: () -> Unit,
    onGraph: () -> Unit,
    onWatchtower: () -> Unit,
    onMore: () -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(HomeState()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastRefresh by remember { mutableLongStateOf(0L) }
    var inventoryQuery by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            if (loading && lastRefresh > 0L) return@launch
            loading = true
            try {
                suspend fun <T> safe(default: T, block: suspend () -> T): T = try {
                    block()
                } catch (e: SessionExpiredException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    default
                }
                val fabric = safe(JSONObject()) { api.intelligenceFabricStatus() }
                val watchtower = safe(JSONObject()) { api.watchtowerStatusV20() }
                val env = safe(JSONObject()) { api.providerEnvironmentStatus() }
                val registry = safe(JSONObject()) { api.freeOsintRegistry() }
                val alertObj = safe(JSONObject()) { api.intelligenceAlertsV14(12) }
                val server = safe(JSONObject()) { api.mobileStatus() }
                val investigations = safe(emptyList<InvestigationSummary>()) { api.investigations(8) }
                val inventory = safe(JSONObject()) { api.assetInventory(500) }
                val inventoryChanges = safe(JSONObject()) { api.assetInventoryChanges(50) }
                val alerts = alertObj.optJSONArray("items") ?: alertObj.optJSONArray("alerts") ?: JSONArray()
                state = HomeState(fabric, watchtower, env, registry, alerts, investigations, server, inventory, inventoryChanges)
                error = null
                lastRefresh = System.currentTimeMillis()
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Dashboard unavailable"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(baseUrl) {
        refresh()
        while (true) {
            delay(60_000)
            refresh()
        }
    }

    val fabricGraph = state.fabric.optJSONObject("graph") ?: state.fabric.optJSONObject("knowledge_graph") ?: state.fabric
    val entities = fabricGraph.optInt("entities", fabricGraph.optInt("entity_count", 0))
    val relationships = fabricGraph.optInt("relationships", fabricGraph.optInt("relationship_count", 0))
    val investigations = state.fabric.optInt("investigations", state.fabric.optInt("investigation_count", state.investigations.size))
    val openAlerts = state.watchtower.optInt("open_alerts", state.alerts.length())
    val urgentAlerts = state.watchtower.optInt("urgent_alerts", 0)
    val configured = state.providerEnv.optInt("configured_count", 0)
    val totalProviders = state.providerEnv.optInt("total", 0)
    val osintTotal = state.registry.optInt("total_sources", state.registry.optJSONArray("sources")?.length() ?: 0)
    val liveSources = state.registry.optInt("active_integrations", state.registry.optInt("active_sources", state.registry.optInt("active_count", 0)))

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            DashboardHero(
                online = error == null,
                urgentAlerts = urgentAlerts,
                investigations = investigations,
                onRefresh = { refresh() },
                refreshing = loading
            )
        }

        item {
            Text("תמונת מצב", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                DashboardMetric("Entities", entities.toString(), Icons.Rounded.Hub, GhostEyePalette.Cyan, Modifier.weight(1f))
                DashboardMetric("Edges", relationships.toString(), Icons.Rounded.Share, GhostEyePalette.Violet, Modifier.weight(1f))
            }
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                DashboardMetric("Alerts", openAlerts.toString(), Icons.Rounded.NotificationsActive, if (urgentAlerts > 0) GhostEyePalette.Rose else GhostEyePalette.Amber, Modifier.weight(1f))
                DashboardMetric("OSINT", if (liveSources > 0) "$liveSources/$osintTotal" else osintTotal.toString(), Icons.Rounded.Public, GhostEyePalette.Emerald, Modifier.weight(1f))
            }
        }

        item {
            SectionHeader("פעולות מהירות", "התחל מהדברים החשובים ביותר")
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                QuickCommand("חקירה חדשה", "OSINT + Fusion", Icons.Rounded.Search, GhostEyePalette.Cyan, onInvestigate, Modifier.weight(1f))
                QuickCommand("Entity Graph", "קשרים ו־timeline", Icons.Rounded.AccountTree, GhostEyePalette.Violet, onGraph, Modifier.weight(1f))
            }
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                QuickCommand("Watchtower", "$openAlerts alerts", Icons.Rounded.Radar, if (urgentAlerts > 0) GhostEyePalette.Rose else GhostEyePalette.Amber, onWatchtower, Modifier.weight(1f))
                QuickCommand("CVE + Sources", "$configured/$totalProviders APIs", Icons.Rounded.Security, GhostEyePalette.Emerald, onMore, Modifier.weight(1f))
            }
        }

        if (urgentAlerts > 0 || state.alerts.length() > 0) {
            item {
                SectionHeader("דורש תשומת לב", if (urgentAlerts > 0) "$urgentAlerts התראות דחופות" else "ההתראות האחרונות") {
                    TextButton(onClick = onWatchtower) { Text("הצג הכל") }
                }
            }
            items((0 until minOf(state.alerts.length(), 3)).mapNotNull { state.alerts.optJSONObject(it) }) { alert ->
                AlertPreviewCard(alert, onWatchtower)
            }
        }

        item {
            AssetInventoryCard(state.inventory, inventoryQuery, { inventoryQuery = it })
        }

        if (state.inventoryChanges.optInt("change_count", 0) > 0) {
            item { InventoryChangesCard(state.inventoryChanges) }
        }

        item {
            SectionCard {
                SectionHeader("מצב המערכת", "Fabric, Watchtower ו־Provider readiness")
                StatusLine(Icons.Rounded.Memory, "Intelligence Fabric", if (entities > 0 || relationships > 0) "ACTIVE" else "READY", entities > 0 || relationships > 0)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                StatusLine(Icons.Rounded.Radar, "Watchtower", if (state.watchtower.length() > 0) "ACTIVE" else "READY", state.watchtower.length() > 0)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                StatusLine(Icons.Rounded.Key, "Provider Vault", "$configured configured", configured > 0)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                StatusLine(Icons.Rounded.CloudDone, "Server", state.server.optString("status", if (error == null) "ONLINE" else "DEGRADED").uppercase(), error == null)
            }
        }

        if (state.investigations.isNotEmpty()) {
            item { SectionHeader("חקירות אחרונות", "הידע האחרון שנשמר ב־Ghost Eye") }
            items(state.investigations.take(5), key = { it.id }) { inv ->
                RecentInvestigationCard(inv, onInvestigate)
            }
        } else if (!loading) {
            item { EmptyState("אין חקירות עדיין", "הרץ Intelligence Fabric ראשון כדי להתחיל לבנות knowledge graph מתמשך.", "פתח חקירה", onInvestigate) }
        }

        if (error != null) {
            item { ErrorPanel(error!!, onRetry = { refresh() }) }
        }
        if (loading && lastRefresh == 0L) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun AssetInventoryCard(inventory: JSONObject, query: String, onQueryChange: (String) -> Unit) {
    val domains = inventory.optJSONArray("domains") ?: JSONArray()
    val ips = inventory.optJSONArray("ips") ?: JSONArray()
    val services = inventory.optJSONArray("services") ?: JSONArray()
    val cves = inventory.optJSONArray("cves") ?: JSONArray()
    val total = domains.length() + ips.length() + services.length() + cves.length()
    SectionCard {
        SectionHeader("כל הדומיינים והנכסים", "מסך אחד לצילום מצב כמו httpx")
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("סינון דומיין / IP / שירות") },
            leadingIcon = { Icon(Icons.Rounded.FilterAlt, contentDescription = null) },
            shape = RoundedCornerShape(14.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InventoryStat("Domains", inventory.optInt("domain_count", 0), Modifier.weight(1f))
            InventoryStat("Subdomains", inventory.optInt("subdomain_count", 0), Modifier.weight(1f))
            InventoryStat("IPs", inventory.optInt("ip_count", 0), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        if (total == 0) {
            Text("אין עדיין תוצאות סריקה שמורות. הרץ סריקת Target כדי לאכלס את הרשימה.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("DOMAINS + SUBDOMAINS", style = MaterialTheme.typography.labelMedium, color = GhostEyePalette.Cyan)
            for (i in 0 until domains.length()) {
                val row = domains.optJSONObject(i) ?: continue
                if (query.isNotBlank() && !row.optString("value").contains(query, ignoreCase = true)) continue
                Text(
                    text = "${row.optString("value")}  ·  ${row.optString("type", "domain")}",
                    modifier = Modifier.padding(vertical = 3.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (ips.length() > 0) {
                Spacer(Modifier.height(8.dp))
                Text("IPS", style = MaterialTheme.typography.labelMedium, color = GhostEyePalette.Violet)
                for (i in 0 until ips.length()) {
                    val value = ips.optJSONObject(i)?.optString("value").orEmpty()
                    if (query.isBlank() || value.contains(query, ignoreCase = true)) Text(value, modifier = Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (services.length() > 0) {
                Spacer(Modifier.height(8.dp))
                Text("SERVICES", style = MaterialTheme.typography.labelMedium, color = GhostEyePalette.Amber)
                for (i in 0 until services.length()) {
                    val row = services.optJSONObject(i) ?: continue
                    if (query.isNotBlank() && !row.optString("value").contains(query, ignoreCase = true) && !row.optString("service").contains(query, ignoreCase = true)) continue
                    Text("${row.optString("value")}  ·  ${row.optString("service", "unknown")}", modifier = Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (cves.length() > 0) {
                Spacer(Modifier.height(8.dp))
                Text("CVEs", style = MaterialTheme.typography.labelMedium, color = GhostEyePalette.Rose)
                for (i in 0 until cves.length()) {
                    val value = cves.optJSONObject(i)?.optString("value").orEmpty()
                    if (query.isBlank() || value.contains(query, ignoreCase = true)) Text(value, modifier = Modifier.padding(vertical = 2.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun InventoryStat(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)) {
        Column(Modifier.padding(9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InventoryChangesCard(payload: JSONObject) {
    val changes = payload.optJSONArray("changes") ?: JSONArray()
    SectionCard {
        SectionHeader("שינויים מאז הסריקה הקודמת", "נכסים חדשים או שהוסרו")
        for (i in 0 until changes.length()) {
            val row = changes.optJSONObject(i) ?: continue
            val added = row.optString("change") == "added"
            Text(
                text = "${if (added) "+" else "−"} ${row.optString("value")}  ·  ${row.optString("kind")}",
                modifier = Modifier.padding(vertical = 3.dp),
                color = if (added) GhostEyePalette.Emerald else GhostEyePalette.Rose,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun DashboardHero(online: Boolean, urgentAlerts: Int, investigations: Int, onRefresh: () -> Unit, refreshing: Boolean) {
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.74f),
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.46f)
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (online) GhostEyePalette.Emerald else GhostEyePalette.Rose))
                            Text(if (online) "SYSTEM ONLINE" else "DEGRADED", style = MaterialTheme.typography.labelSmall, color = if (online) GhostEyePalette.Emerald else GhostEyePalette.Rose)
                        }
                        Spacer(Modifier.height(7.dp))
                        Text("Intelligence Command Center", style = MaterialTheme.typography.headlineSmall)
                        Text("OSINT • Vulnerabilities • Entity Graph • Watchtower", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "רענן")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill("$investigations investigations")
                    if (urgentAlerts > 0) StatusPill("$urgentAlerts urgent", false)
                    else StatusPill("No urgent alerts", true)
                }
            }
        }
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, icon: ImageVector, accent: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.16f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.11f)) {
                Icon(icon, null, tint = accent, modifier = Modifier.padding(8.dp).size(18.dp))
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun QuickCommand(title: String, subtitle: String, icon: ImageVector, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.44f))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = accent.copy(alpha = 0.11f)) {
                    Icon(icon, null, tint = accent, modifier = Modifier.padding(8.dp).size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowForwardIos, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun AlertPreviewCard(alert: JSONObject, onClick: () -> Unit) {
    val payload = alert.optJSONObject("payload") ?: JSONObject()
    val priority = alert.optInt("priority_score", payload.optInt("priority_score", 0))
    val accent = when {
        priority >= 80 -> GhostEyePalette.Rose
        priority >= 55 -> GhostEyePalette.Amber
        else -> GhostEyePalette.Cyan
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.075f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.22f))
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = CircleShape, color = accent.copy(alpha = 0.12f)) {
                Icon(Icons.Rounded.PriorityHigh, null, tint = accent, modifier = Modifier.padding(9.dp).size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(alert.optString("title", alert.optString("kind", "Intelligence alert")), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(payload.optString("entity", alert.optString("summary", "Evidence changed")), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Text(priority.toString(), color = accent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, label: String, value: String, healthy: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        StatusPill(value, healthy)
    }
}

@Composable
private fun RecentInvestigationCard(inv: InvestigationSummary, onClick: () -> Unit) {
    val accent = when {
        inv.riskScore >= 75 -> GhostEyePalette.Rose
        inv.riskScore >= 45 -> GhostEyePalette.Amber
        else -> GhostEyePalette.Emerald
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(13.dp), color = accent.copy(alpha = 0.11f)) {
                Text(inv.riskScore.toString(), modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp), color = accent, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(inv.title.ifBlank { inv.seedValue }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${inv.seedKind.uppercase()} • ${inv.phase} • ${inv.status}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
