package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun AutonomyConsoleCard(
    baseUrl: String,
    investigationId: String,
    investigationStatus: String,
    onSessionExpired: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var status by remember(investigationId) { mutableStateOf<JSONObject?>(null) }
    var correlations by remember(investigationId) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(investigationId) { mutableStateOf(false) }
    var busy by remember(investigationId) { mutableStateOf(false) }
    var error by remember(investigationId) { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true
        try {
            status = api.investigationAutonomy(investigationId)
            correlations = api.investigationCorrelations(investigationId)
            error = null
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "לא ניתן לטעון את מנוע האוטונומיה"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(investigationId, investigationStatus) { refresh() }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("Autonomous Intelligence Core", fontWeight = FontWeight.Bold)
                Text(
                    "תכנון דטרמיניסטי, Policy Broker, שרשרת ראיות וקורלציה בין Jobs.",
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

        status?.let { payload ->
            val mode = payload.optString("mode", "bounded")
            val revision = payload.optInt("plan_revision", 0)
            val currentPlan = payload.optJSONObject("current_plan") ?: JSONObject()
            val budgets = currentPlan.optJSONObject("budgets") ?: JSONObject()
            val chain = payload.optJSONObject("evidence_chain") ?: JSONObject()
            val chainValid = chain.optBoolean("valid", true)
            val eventCount = chain.optInt("event_count", 0)
            val correlationMetrics = correlations?.optJSONObject("metrics") ?: JSONObject()

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("מצב", autonomyModeLabel(mode), Modifier.weight(1f))
                MetricCard("Plan", "#$revision", Modifier.weight(1f))
                MetricCard("ראיות", eventCount.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Nodes", budgets.optInt("nodes_remaining", 0).toString(), Modifier.weight(1f))
                MetricCard("Scans", budgets.optInt("target_scans_remaining", 0).toString(), Modifier.weight(1f))
                MetricCard("Clusters", correlationMetrics.optInt("clusters", 0).toString(), Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))
            Text(
                if (chainValid) "שרשרת הראיות תקינה" else "אזהרה: אימות שרשרת הראיות נכשל",
                color = if (chainValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )

            val terminal = investigationStatus.lowercase() in setOf("completed", "failed", "cancelled")
            Spacer(Modifier.height(12.dp))
            Text("רמת אוטונומיה", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(
                    "observe" to "צפייה בלבד",
                    "assist" to "סיוע והצעות",
                    "bounded" to "אוטונומי מוגבל"
                ).forEach { (value, label) ->
                    OutlinedButton(
                        onClick = {
                            if (busy || terminal || value == mode) return@OutlinedButton
                            scope.launch {
                                busy = true
                                try {
                                    api.setInvestigationAutonomy(investigationId, value)
                                    refresh()
                                } catch (e: SessionExpiredException) {
                                    onSessionExpired()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.message ?: "שינוי מצב האוטונומיה נכשל"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && !terminal,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (value == mode) "✓ $label" else label)
                    }
                }
            }

            val steps = currentPlan.optJSONArray("steps") ?: JSONArray()
            if (steps.length() > 0) {
                Spacer(Modifier.height(14.dp))
                Text("תוכנית פעולה", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                for (i in 0 until minOf(steps.length(), 6)) {
                    val step = steps.optJSONObject(i) ?: continue
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 7.dp))
                    val automatic = step.optBoolean("automatic", false)
                    Text(step.optString("id", "step"), fontWeight = FontWeight.SemiBold)
                    Text(
                        "${if (automatic) "אוטומטי" else "הצעה בלבד"} • ${step.optString("state", "unknown")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    step.optString("reason").takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun autonomyModeLabel(mode: String): String = when (mode.lowercase()) {
    "observe" -> "Observe"
    "assist" -> "Assist"
    "bounded" -> "Bounded"
    else -> mode
}
