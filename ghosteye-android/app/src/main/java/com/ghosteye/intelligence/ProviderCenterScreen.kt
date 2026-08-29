package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ProviderCenterScreen(baseUrl: String, modifier: Modifier = Modifier, onSessionExpired: () -> Unit) {
    var advanced by remember { mutableStateOf(false) }
    if (advanced) {
        Column(modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { advanced = false }) { Icon(Icons.Rounded.ArrowBack, contentDescription = "חזרה") }
                    Text("כלי מקורות מתקדמים", style = MaterialTheme.typography.titleMedium)
                }
            }
            CyberOperationsScreen(baseUrl, Modifier.weight(1f), onSessionExpired)
        }
        return
    }

    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf<JSONObject?>(null) }
    var environment by remember { mutableStateOf<JSONObject?>(null) }
    var registry by remember { mutableStateOf<JSONObject?>(null) }
    var health by remember { mutableStateOf<JSONObject?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var secret by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                providers = api.providerVaultStatus()
                environment = api.providerEnvironmentStatus()
                registry = api.freeOsintRegistry()
                health = try { api.intelligenceSourceHealth() } catch (_: Exception) { null }
                error = null
            } catch (e: SessionExpiredException) { onSessionExpired() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "טעינת המקורות נכשלה" }
            finally { loading = false }
        }
    }

    LaunchedEffect(baseUrl) { refresh() }

    val rows = remember(providers?.toString(), query) {
        val arr = providers?.optJSONArray("providers") ?: JSONArray()
        buildList {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { row ->
                val needle = query.trim().lowercase()
                val hay = listOf(row.optString("provider"), row.optString("category"), row.optString("display_name")).joinToString(" ").lowercase()
                if (needle.isBlank() || hay.contains(needle)) add(row)
            }
        }.sortedWith(compareByDescending<JSONObject> { it.optBoolean("configured", false) }.thenBy { it.optString("category") }.thenBy { it.optString("provider") })
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            CommandHero(
                eyebrow = "OSINT SOURCE CENTER",
                title = "מקורות ו־API",
                subtitle = "כל מקורות ה־OSINT, ה־AI providers וה־API keys שלך במקום אחד — בלי לחשוף secrets חזרה לטלפון."
            )
        }

        item {
            val configured = environment?.optInt("configured_count", 0) ?: 0
            val totalProviders = environment?.optInt("total", rows.size) ?: rows.size
            val registryTotal = registry?.optInt("total_sources", registry?.optJSONArray("sources")?.length() ?: 0) ?: 0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Configured", configured.toString(), Modifier.weight(1f), GhostEyePalette.Emerald)
                MetricCard("Providers", totalProviders.toString(), Modifier.weight(1f))
                MetricCard("OSINT", registryTotal.toString(), Modifier.weight(1f), GhostEyePalette.Violet)
            }
        }

        item {
            SectionCard {
                SectionHeader("חיפוש וניהול", "Configured providers מוצגים ראשונים") {
                    IconButton(onClick = { refresh() }, enabled = !loading) { Icon(Icons.Rounded.Refresh, contentDescription = "רענן") }
                }
                OutlinedTextField(
                    query,
                    { query = it.take(80) },
                    Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    label = { Text("חפש provider או קטגוריה") }
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                health?.let { h ->
                    val healthy = h.optInt("healthy", h.optInt("healthy_count", 0))
                    val degraded = h.optInt("degraded", h.optInt("degraded_count", 0))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (healthy > 0) StatusPill("$healthy healthy", true)
                        if (degraded > 0) StatusPill("$degraded degraded", false)
                    }
                }
            }
        }

        selectedProvider?.let { name ->
            item {
                SectionCard {
                    SectionHeader("הגדרת $name", "המפתח נשלח ל־Vault המוצפן ואינו מוצג חזרה") {
                        IconButton(onClick = { selectedProvider = null; secret = "" }) { Icon(Icons.Rounded.Close, null) }
                    }
                    OutlinedTextField(
                        value = secret,
                        onValueChange = { secret = it.take(16384) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("API Key / Token") },
                        leadingIcon = { Icon(Icons.Rounded.Key, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    Button(
                        onClick = {
                            val value = secret
                            if (value.isBlank()) return@Button
                            scope.launch {
                                saving = true
                                try {
                                    api.saveProviderSecret(name, value, reasoning = true)
                                    secret = ""
                                    selectedProvider = null
                                    refresh()
                                } catch (e: SessionExpiredException) { onSessionExpired() }
                                catch (e: Exception) { error = e.message ?: "שמירת המפתח נכשלה" }
                                finally { saving = false }
                            }
                        },
                        enabled = secret.isNotBlank() && !saving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Lock, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (saving) "שומר…" else "שמור ב־Provider Vault")
                    }
                }
            }
        }

        if (rows.isEmpty() && !loading) {
            item { EmptyState("לא נמצאו מקורות", "נסה לחפש בשם אחר או לרענן את Provider Registry.") }
        } else {
            items(rows, key = { it.optString("provider") }) { row ->
                val name = row.optString("provider")
                val configured = row.optBoolean("configured", false)
                val enabled = row.optBoolean("enabled", false)
                SectionCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) {
                            Icon(
                                if (row.optString("category").contains("ai", true)) Icons.Rounded.Psychology else Icons.Rounded.Language,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(10.dp).size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(row.optString("display_name", name), style = MaterialTheme.typography.titleMedium)
                            Text(row.optString("category", "OSINT"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(if (configured) if (enabled) "ACTIVE" else "READY" else "SETUP", if (configured) enabled else null)
                    }
                    if (configured) {
                        Text("Secret: ••••${row.optString("last4")}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { selectedProvider = name; secret = "" }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Edit, null); Spacer(Modifier.width(5.dp)); Text("החלף key")
                            }
                            Button(onClick = {
                                scope.launch {
                                    try { api.updateProviderSettings(name, enabled = !enabled); refresh() }
                                    catch (e: SessionExpiredException) { onSessionExpired() }
                                    catch (e: Exception) { error = e.message ?: "עדכון provider נכשל" }
                                }
                            }, modifier = Modifier.weight(1f)) { Text(if (enabled) "השבת" else "הפעל") }
                        }
                    } else {
                        Button(onClick = { selectedProvider = name; secret = "" }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("הגדר API")
                        }
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = { advanced = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Tune, null)
                Spacer(Modifier.width(7.dp))
                Text("כלי מקורות מתקדמים")
            }
        }
    }
}
