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
fun VerifiedInvestigationCard(
    baseUrl: String,
    investigationId: String,
    onSessionExpired: () -> Unit
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var result by remember(investigationId) { mutableStateOf<JSONObject?>(null) }
    var busy by remember(investigationId) { mutableStateOf(false) }
    var error by remember(investigationId) { mutableStateOf<String?>(null) }

    fun runReview() {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val copilot = api.investigationCopilotV14(investigationId)
                val challenge = api.investigationChallengeV14(investigationId)
                val review = api.investigationMultiReviewV14(investigationId)
                result = JSONObject().put("copilot", copilot).put("challenge", challenge).put("review", review)
                error = null
            } catch (e: SessionExpiredException) {
                onSessionExpired()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "בדיקת הראיות נכשלה"
            } finally {
                busy = false
            }
        }
    }

    SectionCard {
        Text("Verified Investigation Review", fontWeight = FontWeight.Bold)
        Text("Copilot מציע צעדי המשך בלבד; challenge מחפש ראיות נגדיות ו־multi-review בודק כיסוי/סתירות בלי shell חופשי.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { runReview() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "בודק…" else "הרץ ביקורת ראיות")
        }
        if (busy) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        result?.let { r ->
            Spacer(Modifier.height(10.dp))
            val actions = r.optJSONObject("copilot")?.optJSONArray("actions")?.length() ?: 0
            val hypotheses = r.optJSONObject("challenge")?.optJSONArray("reviews")?.length() ?: 0
            val review = r.optJSONObject("review") ?: JSONObject()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("צעדים", actions.toString(), Modifier.weight(1f))
                MetricCard("Challenges", hypotheses.toString(), Modifier.weight(1f))
                MetricCard("Review", if (review.length() > 0) "✓" else "—", Modifier.weight(1f))
            }
        }
    }
}
