package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
fun SettingsScreen(
    baseUrl: String,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onSessionExpired: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var health by remember { mutableStateOf<JSONObject?>(null) }
    var diagnostics by remember { mutableStateOf<JSONObject?>(null) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var crashPresent by remember { mutableStateOf(CrashReporter.hasCrash(context)) }

    fun checkServer() {
        scope.launch {
            checking = true
            error = null
            try {
                health = api.health()
                diagnostics = api.diagnostics()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is SessionExpiredException) onSessionExpired()
                else error = friendlyError(e, "לא ניתן להגיע לשרת")
            } finally {
                checking = false
            }
        }
    }

    LaunchedEffect(Unit) { checkServer() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { PageTitle("הגדרות", "חיבור, תצוגה, אבחון ואבטחת החשבון") }

        item {
            SectionCard {
                Text("חשבון", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(ServerConfig.OWNER_EMAIL_MASKED, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text("Single-user locked mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            SectionCard {
                Text("חיבור מאובטח", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Secure Cloud • HTTPS", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text(if (health?.optString("status") == "ok") "מחובר ומאובטח" else if (checking) "בודק…" else "לא זמין")
                        health?.optString("version")?.takeIf { it.isNotBlank() }?.let {
                            Text("Server $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OutlinedButton(onClick = { checkServer() }, enabled = !checking) { Text("בדוק") }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        diagnostics?.let { d ->
            item {
                SectionCard {
                    Text("מצב תשתית", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    val dbOk = d.optJSONObject("database")?.optBoolean("ok", false) == true
                    val redisOk = d.optJSONObject("redis")?.optBoolean("ok", false) == true
                    val workers = d.optJSONArray("workers")?.length() ?: 0
                    val queue = d.optJSONObject("queue")
                    val queued = queue?.optInt("queued_total", 0) ?: 0
                    val processing = queue?.optInt("processing", 0) ?: 0
                    val storage = d.optJSONObject("storage")
                    val used = storage?.optDouble("used_percent", 0.0) ?: 0.0
                    DiagnosticLine("PostgreSQL", if (dbOk) "תקין" else "תקלה", dbOk)
                    DiagnosticLine("Redis", if (redisOk) "תקין" else "תקלה", redisOk)
                    DiagnosticLine("Workers", workers.toString(), workers > 0)
                    DiagnosticLine("Queue", if (queue != null) "$queued ממתינים • $processing פעילים" else "לא זמין", redisOk && queue != null)
                    DiagnosticLine("Storage", "${"%.1f".format(used)}% בשימוש", used < 90.0)
                    val ai = d.optJSONObject("ai")
                    val aiConfigured = ai?.optBoolean("configured", false) == true
                    val aiName = if (aiConfigured) ai?.optString("model", "DeepSeek") ?: "DeepSeek" else "כבוי (פרטי)"
                    DiagnosticLine("AI חיצוני", aiName, true)
                }
            }
        }

        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("מצב כהה", fontWeight = FontWeight.Bold)
                        Text("הבחירה נשמרת גם אחרי סגירת האפליקציה", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
                }
            }
        }

        if (crashPresent) {
            item {
                SectionCard {
                    Text("אבחון קריסה", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                    Text("נמצא crash log מקומי מהפעלה קודמת. הוא נשמר רק בטלפון ולא נשלח אוטומטית.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { CrashReporter.clear(context); crashPresent = false }) { Text("נקה crash log") }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("התנתקות", fontWeight = FontWeight.Bold) }
        }

        item {
            Text(
                "Ghost Eye Phone ${BuildConfig.VERSION_NAME} • HTTPS only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
    }
}

private fun friendlyError(e: Exception, fallback: String): String = when (e) {
    is ApiException -> "שגיאת שרת ${e.code}: ${e.message}"
    else -> e.message?.takeIf { it.isNotBlank() } ?: fallback
}
