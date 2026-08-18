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
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun checkServer() {
        scope.launch {
            checking = true
            error = null
            try { health = api.health() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (e is SessionExpiredException) onSessionExpired() else error = e.message ?: "לא ניתן להגיע לשרת" }
            finally { checking = false }
        }
    }

    LaunchedEffect(Unit) { checkServer() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { PageTitle("הגדרות", "חיבור, תצוגה ואבטחת החשבון") }

        item {
            SectionCard {
                Text("חשבון", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(ServerConfig.OWNER_EMAIL, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text("Single-user locked mode", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            SectionCard {
                Text("שרת", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(baseUrl, style = MaterialTheme.typography.bodyMedium)
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

        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("מצב כהה", fontWeight = FontWeight.Bold)
                        Text("ממשק Ghost Eye כהה וממוקד", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("התנתקות", fontWeight = FontWeight.Bold)
            }
        }

        item {
            Text(
                "Ghost Eye Phone 10.0.1 • HTTPS only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
