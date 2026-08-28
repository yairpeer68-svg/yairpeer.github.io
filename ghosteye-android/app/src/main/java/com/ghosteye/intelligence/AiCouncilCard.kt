package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun AiCouncilCard(
    baseUrl: String,
    investigationId: String,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var question by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("smart") }
    var expanded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SectionCard {
        Text("Ghost AI Council", fontWeight = FontWeight.Bold)
        Text("OpenAI / DeepSeek מקבלים רק evidence מהחקירה; claims ללא Evidence ID מסומנים כלא־מאומתים.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("AI mode: $mode") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("economy", "smart", "maximum", "local_private").forEach { value ->
                    DropdownMenuItem(text = { Text(value) }, onClick = { mode = value; expanded = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = question,
            onValueChange = { question = it.take(4000) },
            label = { Text("שאל את Ghost") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5
        )
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = question.isNotBlank() && !loading,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val q = question.trim()
                scope.launch {
                    loading = true
                    try {
                        result = api.aiCouncilV15(investigationId, q, mode)
                        error = null
                    } catch (e: SessionExpiredException) { onSessionExpired() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "AI Council נכשל" }
                    finally { loading = false }
                }
            }
        ) { Text(if (loading) "מנתח…" else "נתח עם AI Council") }
        if (loading) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        result?.let { r ->
            Spacer(Modifier.height(10.dp))
            Text("Agreement: ${r.optString("agreement", "unknown")}", fontWeight = FontWeight.SemiBold)
            Text("Evidence: ${r.optInt("evidence_count", 0)}", style = MaterialTheme.typography.labelSmall)
            r.optString("summary").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
