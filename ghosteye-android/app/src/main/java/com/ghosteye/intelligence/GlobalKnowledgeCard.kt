package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun GlobalKnowledgeCard(
    baseUrl: String,
    investigationId: String,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var payload by remember(investigationId) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(investigationId) { mutableStateOf(false) }
    var syncing by remember(investigationId) { mutableStateOf(false) }
    var error by remember(investigationId) { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        try {
            payload = api.investigationKnowledge(investigationId)
            error = null
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "לא ניתן לטעון את גרף הידע"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(investigationId) { reload() }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text("Global Knowledge Graph", fontWeight = FontWeight.Bold)
                Text(
                    "ישות קנונית, קשרים היסטוריים וזיכרון חוצה־חקירות — מקומי ומופרד לפי בעלים.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { scope.launch { reload() } }, enabled = !loading && !syncing) {
                Icon(Icons.Rounded.Refresh, contentDescription = "רענון")
            }
        }
        if (loading) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        payload?.let { result ->
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("ישויות", result.optInt("entity_count", 0).toString(), Modifier.weight(1f))
                MetricCard("קשרים", result.optInt("relationship_count", 0).toString(), Modifier.weight(1f))
                MetricCard("קבצים", result.optInt("artifact_jobs_synced", 0).toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "הסנכרון idempotent: רענון חוזר אינו מנפח מונים או ראיות.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        syncing = true
                        try {
                            api.syncInvestigationKnowledge(investigationId)
                            reload()
                        } catch (e: SessionExpiredException) {
                            onSessionExpired()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: "סנכרון גרף הידע נכשל"
                        } finally {
                            syncing = false
                        }
                    }
                },
                enabled = !syncing && !loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (syncing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("סנכרן לגרף הגלובלי")
            }
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
