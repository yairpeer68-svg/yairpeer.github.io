package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
fun CyberOperationsScreen(
    baseUrl: String,
    modifier: Modifier = Modifier,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()

    var capabilities by remember { mutableStateOf<JSONObject?>(null) }
    var control by remember { mutableStateOf<JSONObject?>(null) }
    var providers by remember { mutableStateOf<JSONObject?>(null) }
    var usage by remember { mutableStateOf<JSONObject?>(null) }
    var cveSources by remember { mutableStateOf<JSONObject?>(null) }
    var cveResults by remember { mutableStateOf(JSONArray()) }
    var incidents by remember { mutableStateOf(JSONArray()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var provider by remember { mutableStateOf("openai") }
    var providerMenu by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var cveQuery by remember { mutableStateOf("") }
    var kevOnly by remember { mutableStateOf(false) }

    val providerNames = listOf("openai", "deepseek", "virustotal", "urlscan", "securitytrails", "shodan", "greynoise", "abuseipdb", "certspotter", "censys", "nvd", "github", "misp", "taxii")

    fun refresh() {
        if (loading) return
        scope.launch {
            loading = true
            try {
                capabilities = api.cyberCapabilities()
                control = api.cyberControl()
                providers = api.providerVaultStatus()
                usage = api.providerUsageV15()
                cveSources = api.cveSourcesV15()
                cveResults = api.cveSearchV15(cveQuery, 2026, kevOnly, 100).optJSONArray("results") ?: JSONArray()
                incidents = api.cyberIncidentsV15()
                error = null
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "טעינת מרכז הסייבר נכשלה"
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
                Text("Cyber Operations 15", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("SOC, CVE‑2026, Provider Vault ויכולות סייבר מורשות. פעולות אקטיביות נשארות חסומות כברירת מחדל.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text(if (loading) "טוען…" else "רענן מרכז סייבר")
                }
                if (loading) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        }

        control?.let { c ->
            item {
                SectionCard {
                    Text("Safety Control", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Kill Switch: ${if (c.optBoolean("global_kill_switch", true)) "ON" else "OFF"}")
                    Text("Active operations: ${if (c.optBoolean("active_operations_enabled", false)) "Enabled" else "Disabled"}")
                    Text("Human approval: ${if (c.optBoolean("require_human_approval", true)) "Required" else "Not required"}")
                    Spacer(Modifier.height(6.dp))
                    Text("המסך לא מפעיל סריקה אקטיבית; Scope + ROE + approval מנוהלים בצד השרת לפני כל פעולה.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        capabilities?.let { payload ->
            val features = payload.optJSONArray("features") ?: JSONArray()
            item {
                SectionCard {
                    Text("Cyber Capability Matrix", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCard("סה״כ", payload.optInt("feature_count", features.length()).toString(), Modifier.weight(1f))
                        MetricCard("Incidents", incidents.length().toString(), Modifier.weight(1f))
                        MetricCard("CVE‑2026", cveResults.length().toString(), Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            SectionCard {
                Text("Secure Provider Vault", fontWeight = FontWeight.Bold)
                Text("המפתח המלא נשמר מוצפן בשרת ולא חוזר לאפליקציה.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Box {
                    OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(provider) }
                    DropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                        providerNames.forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { provider = name; providerMenu = false; secret = "" })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it.take(16384) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key / Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val value = secret
                        if (value.isBlank()) return@Button
                        scope.launch {
                            try {
                                api.saveProviderSecret(provider, value, provider in setOf("openai", "deepseek"))
                                secret = ""
                                providers = api.providerVaultStatus()
                                error = null
                            } catch (e: SessionExpiredException) { onSessionExpired() }
                            catch (e: Exception) { error = e.message ?: "שמירת המפתח נכשלה"; secret = "" }
                        }
                    },
                    enabled = secret.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("שמור בצורה מוצפנת") }
            }
        }

        providers?.optJSONArray("providers")?.let { rows ->
            items((0 until rows.length()).mapNotNull { rows.optJSONObject(it) }, key = { it.optString("provider") }) { row ->
                SectionCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(row.optString("provider"), fontWeight = FontWeight.SemiBold)
                            Text(row.optString("category"), style = MaterialTheme.typography.labelSmall)
                        }
                        if (row.optBoolean("configured", false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("••••${row.optString("last4")}", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = row.optBoolean("enabled", false),
                                    onCheckedChange = { desired ->
                                        scope.launch {
                                            try {
                                                api.updateProviderSettings(row.optString("provider"), enabled = desired)
                                                providers = api.providerVaultStatus()
                                                error = null
                                            } catch (e: SessionExpiredException) { onSessionExpired() }
                                            catch (e: Exception) { error = e.message ?: "עדכון הספק נכשל" }
                                        }
                                    }
                                )
                            }
                        } else {
                            Text("לא מוגדר", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    val perms = row.optJSONObject("permissions") ?: JSONObject()
                    if (row.optBoolean("submission_capable", false) && row.optBoolean("configured", false)) {
                        Text("External submission permissions", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (perms.has("url_submission")) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("URL submission", style = MaterialTheme.typography.bodySmall)
                                Switch(
                                    checked = perms.optBoolean("url_submission", false),
                                    onCheckedChange = { desired ->
                                        scope.launch {
                                            try {
                                                api.updateProviderSettings(row.optString("provider"), permissions = JSONObject().put("url_submission", desired))
                                                providers = api.providerVaultStatus()
                                                error = null
                                            } catch (e: SessionExpiredException) { onSessionExpired() }
                                            catch (e: Exception) { error = e.message ?: "עדכון הרשאה נכשל" }
                                        }
                                    }
                                )
                            }
                        }
                        if (perms.has("file_submission")) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("File submission", style = MaterialTheme.typography.bodySmall)
                                Switch(
                                    checked = perms.optBoolean("file_submission", false),
                                    onCheckedChange = { desired ->
                                        scope.launch {
                                            try {
                                                api.updateProviderSettings(row.optString("provider"), permissions = JSONObject().put("file_submission", desired))
                                                providers = api.providerVaultStatus()
                                                error = null
                                            } catch (e: SessionExpiredException) { onSessionExpired() }
                                            catch (e: Exception) { error = e.message ?: "עדכון הרשאה נכשל" }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard {
                Text("CVE‑2026 Intelligence Lake", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = cveQuery, onValueChange = { cveQuery = it.take(128) }, label = { Text("חיפוש CVE / מוצר / vendor") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("CISA KEV בלבד", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = kevOnly, onCheckedChange = { kevOnly = it })
                }
                Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("חפש ב־CVE‑2026") }
                cveSources?.let { Text("Source health: ${it.optString("status", "available")}", style = MaterialTheme.typography.labelSmall) }
            }
        }

        items((0 until cveResults.length()).mapNotNull { cveResults.optJSONObject(it) }.take(50), key = { it.optString("cve_id") }) { row ->
            SectionCard {
                Text(row.optString("cve_id", "CVE"), fontWeight = FontWeight.Bold)
                Text("CVSS ${row.optDouble("cvss_score", 0.0)} • EPSS ${row.optDouble("epss_score", 0.0)} • ${if (row.optBoolean("kev", false)) "KEV" else "not KEV"}", style = MaterialTheme.typography.bodySmall)
                row.optString("description").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 4) }
            }
        }

        usage?.optJSONArray("providers")?.let { rows ->
            item {
                SectionCard {
                    Text("AI / Provider Usage", fontWeight = FontWeight.Bold)
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        Text("${row.optString("provider")}: ${row.optInt("requests")} requests • \$${"%.4f".format(row.optDouble("estimated_cost", 0.0))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
