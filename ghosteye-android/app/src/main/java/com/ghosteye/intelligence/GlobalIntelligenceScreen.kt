package com.ghosteye.intelligence

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowBack
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun GlobalIntelligenceScreen(
    baseUrl: String,
    modifier: Modifier = Modifier,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(listOf<JSONObject>()) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var graph by remember { mutableStateOf<JSONObject?>(null) }
    var timeline by remember { mutableStateOf<JSONArray?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var watchMessage by remember { mutableStateOf<String?>(null) }

    suspend fun loadEntity(id: String) {
        loading = true
        watchMessage = null
        try {
            selected = api.globalEntity(id)
            graph = api.globalEntityGraph(id, depth = 2, maxNodes = 150)
            timeline = api.globalEntityTimeline(id, 100).optJSONArray("events") ?: JSONArray()
            error = null
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "טעינת הישות נכשלה"
        } finally {
            loading = false
        }
    }

    fun search() {
        val token = query.trim()
        if (token.isBlank() || loading) return
        scope.launch {
            loading = true
            try {
                val payload = api.globalIntelligenceSearch(token)
                val array = payload.optJSONArray("results") ?: JSONArray()
                results = buildList {
                    for (i in 0 until array.length()) array.optJSONObject(i)?.let(::add)
                }
                selected = null
                graph = null
                timeline = null
                error = null
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "החיפוש נכשל"
            } finally {
                loading = false
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Global Intelligence Graph", fontWeight = FontWeight.Bold)
                        Text(
                            "חפש domain, IP, hash, ASN, package או URL בכל היסטוריית החקירות שלך.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(256) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("ישות לחיפוש") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { search() },
                    enabled = query.isNotBlank() && !loading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("חפש בזיכרון הגלובלי") }
                if (loading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        selected?.let { details ->
            val entity = details.optJSONObject("entity") ?: JSONObject()
            item {
                SectionCard {
                    TextButton(onClick = { selected = null; graph = null; timeline = null }) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("חזרה לתוצאות")
                    }
                    Text(entity.optString("display_value", entity.optString("canonical_value", "ישות")), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(entity.optString("entity_type"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("חקירות", entity.optInt("investigation_count", 0).toString(), Modifier.weight(1f))
                        MetricCard("תצפיות", entity.optInt("occurrence_count", 0).toString(), Modifier.weight(1f))
                        MetricCard("Risk", "${entity.optInt("risk_score", 0)}/100", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("First seen: ${entity.optString("first_seen", "—")}", style = MaterialTheme.typography.labelSmall)
                    Text("Last seen: ${entity.optString("last_seen", "—")}", style = MaterialTheme.typography.labelSmall)
                    Text("Confidence: ${(entity.optDouble("max_confidence", 0.0) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val type = entity.optString("entity_type")
                            val value = entity.optString("canonical_value")
                            if (type.isNotBlank() && value.isNotBlank()) scope.launch {
                                try {
                                    api.createEntityWatchlistV14("מעקב: ${entity.optString("display_value", value).take(120)}", type, value)
                                    watchMessage = "נוסף למעקב"
                                } catch (e: SessionExpiredException) { onSessionExpired() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { watchMessage = e.message ?: "הוספה למעקב נכשלה" }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("הוסף ל־Watchlist") }
                    watchMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                }
            }

            val rels = details.optJSONArray("relationships") ?: JSONArray()
            if (rels.length() > 0) {
                item { Text("קשרים", fontWeight = FontWeight.Bold) }
                items((0 until minOf(rels.length(), 40)).mapNotNull { rels.optJSONObject(it) }, key = { it.optString("id") }) { rel ->
                    val other = rel.optJSONObject("other_entity") ?: JSONObject()
                    val otherId = other.optString("id")
                    SectionCard(
                        modifier = Modifier.clickable(enabled = otherId.isNotBlank()) { scope.launch { loadEntity(otherId) } }
                    ) {
                        Text(rel.optString("relation_type", "related"), fontWeight = FontWeight.SemiBold)
                        Text(
                            "${if (rel.optString("direction") == "outgoing") "→" else "←"} ${other.optString("display_value", other.optString("canonical_value", "ישות"))}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${other.optString("entity_type")} • ${(rel.optDouble("confidence", 0.0) * 100).toInt()}% • ${rel.optInt("evidence_count", 0)} ראיות",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            graph?.let { g ->
                item {
                    SectionCard {
                        val nodes = g.optJSONArray("nodes")?.length() ?: 0
                        val edges = g.optJSONArray("edges")?.length() ?: 0
                        Text("Neighborhood", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCard("Nodes", nodes.toString(), Modifier.weight(1f))
                            MetricCard("Edges", edges.toString(), Modifier.weight(1f))
                        }
                        if (g.optBoolean("truncated", false)) {
                            Spacer(Modifier.height(6.dp))
                            Text("התצוגה קוצצה להגבלת בטיחות/ביצועים.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            timeline?.let { events ->
                if (events.length() > 0) {
                    item { Text("Timeline", fontWeight = FontWeight.Bold) }
                    items((0 until minOf(events.length(), 30)).mapNotNull { events.optJSONObject(it) }, key = { it.optString("occurrence_id") }) { event ->
                        SectionCard {
                            Text(event.optString("investigation_title", "חקירה"), fontWeight = FontWeight.SemiBold)
                            Text("${event.optString("origin_type")} • ${event.optString("source_id", "local")}", style = MaterialTheme.typography.bodySmall)
                            Text(event.optString("observed_at", "—"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } ?: run {
            if (results.isNotEmpty()) {
                item { Text("תוצאות (${results.size})", fontWeight = FontWeight.Bold) }
                items(results, key = { it.optString("id") }) { entity ->
                    val id = entity.optString("id")
                    SectionCard(modifier = Modifier.clickable { scope.launch { loadEntity(id) } }) {
                        Text(entity.optString("display_value", entity.optString("canonical_value", "ישות")), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${entity.optString("entity_type")} • ${entity.optInt("investigation_count", 0)} חקירות • ${entity.optInt("occurrence_count", 0)} תצפיות",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
