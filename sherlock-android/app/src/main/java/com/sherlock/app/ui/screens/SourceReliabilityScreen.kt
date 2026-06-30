package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI

private val trustedDomains = setOf(
    "wikipedia.org", "gov.il", "gov", "edu", "reuters.com", "apnews.com",
    "bbc.com", "nytimes.com", "ynet.co.il", "haaretz.co.il", "globes.co.il",
    "linkedin.com", "github.com"
)

private val lowTrustHints = setOf(
    "blogspot.", "wordpress.com", "wixsite.com", "weebly.com", "freehost",
    "000webhost", "tk", "ml", "ga", "cf"
)

private data class ReliabilityResult(val score: Int, val label: String, val reasons: List<String>)

private fun evaluateReliability(urlText: String): ReliabilityResult? {
    val normalized = if (!urlText.startsWith("http")) "https://$urlText" else urlText
    val uri = try { URI(normalized) } catch (_: Exception) { return null }
    val host = uri.host ?: return null

    var score = 50
    val reasons = mutableListOf<String>()

    if (normalized.startsWith("https://")) {
        score += 10; reasons.add("משתמש בחיבור מאובטח (HTTPS)")
    } else {
        score -= 15; reasons.add("אינו משתמש ב-HTTPS")
    }

    if (trustedDomains.any { host.endsWith(it) }) {
        score += 30; reasons.add("דומיין ידוע ואמין")
    }

    if (lowTrustHints.any { host.contains(it) }) {
        score -= 20; reasons.add("פלטפורמת אחסון חינמית/דומיין חשוד")
    }

    if (host.count { it == '.' } >= 3) {
        score -= 10; reasons.add("מבנה דומיין מורכב (תתי-דומיינים רבים)")
    }

    if (host.split(".").firstOrNull()?.any { it.isDigit() } == true) {
        score -= 5; reasons.add("שם הדומיין כולל מספרים, ייתכן ולא רשמי")
    }

    score = score.coerceIn(0, 100)
    val label = when {
        score >= 75 -> "אמינות גבוהה"
        score >= 50 -> "אמינות בינונית"
        score >= 25 -> "אמינות נמוכה"
        else -> "אמינות נמוכה מאוד"
    }
    if (reasons.isEmpty()) reasons.add("לא נמצאו סימנים מיוחדים")
    return ReliabilityResult(score, label, reasons)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceReliabilityScreen(onNavigateBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val result = remember(input) { if (input.isNotBlank()) evaluateReliability(input) else null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("מד אמינות מקור", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Text("הערכה היוריסטית מקומית של מהימנות מקור, אינה מהווה אימות עובדתי", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("כתובת המקור (URL)") },
                leadingIcon = { Icon(Icons.Default.Link, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(20.dp))

            result?.let { res ->
                val color = when {
                    res.score >= 75 -> MaterialTheme.colorScheme.primary
                    res.score >= 50 -> Color(0xFFFFB300)
                    else -> MaterialTheme.colorScheme.error
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${res.score}/100", fontWeight = FontWeight.Bold, fontSize = 32.sp, color = color)
                        Text(res.label, fontWeight = FontWeight.Medium, color = color)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { res.score / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = color,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("גורמים שהשפיעו על הציון:", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                res.reasons.forEach { reason ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Icon(Icons.Default.Circle, null, Modifier.size(6.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } ?: if (input.isNotBlank()) {
                Text("כתובת לא תקינה", color = MaterialTheme.colorScheme.error)
            } else Unit
        }
    }
}
