package com.ghosteye.intelligence

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private enum class MoreMode { Hub, Vulnerabilities, Providers }

@Composable
fun MoreCenterScreen(baseUrl: String, modifier: Modifier = Modifier, onSessionExpired: () -> Unit) {
    var mode by remember { mutableStateOf(MoreMode.Hub) }
    when (mode) {
        MoreMode.Vulnerabilities -> Column(modifier.fillMaxSize()) {
            MoreBackBar("Vulnerability Intelligence") { mode = MoreMode.Hub }
            VulnerabilityCenterScreen(baseUrl, Modifier.weight(1f), onSessionExpired)
        }
        MoreMode.Providers -> Column(modifier.fillMaxSize()) {
            MoreBackBar("OSINT Sources & APIs") { mode = MoreMode.Hub }
            ProviderCenterScreen(baseUrl, Modifier.weight(1f), onSessionExpired)
        }
        MoreMode.Hub -> androidx.compose.foundation.lazy.LazyColumn(
            modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { CommandHero("INTELLIGENCE MODULES", "מרכז כלים", "Vulnerability Intelligence, OSINT providers והגדרות מתקדמות — בלי להעמיס על הניווט הראשי.") }
            item { MoreModuleCard("Vulnerability Intelligence", "CVE • EPSS • KEV • OSV • GitHub Advisories • Packages", Icons.Rounded.Security, GhostEyePalette.Rose) { mode = MoreMode.Vulnerabilities } }
            item { MoreModuleCard("OSINT Source Center", "Providers • API Vault • Source health • Registry", Icons.Rounded.Public, GhostEyePalette.Cyan) { mode = MoreMode.Providers } }
            item {
                SectionCard {
                    SectionHeader("מבנה 2.0", "כל מודול נשאר נפרד וברור")
                    Text("ה־Home מציג תמונת מצב. Investigation מבצע חקירה. Entity Graph מציג קשרים. Watchtower מטפל בשינויים. כאן נמצאים כלי ה־CVE והמקורות.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MoreBackBar(title: String, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "חזרה") }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MoreModuleCard(title: String, subtitle: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.90f)),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.18f))
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = 0.11f)) {
                Icon(icon, null, tint = accent, modifier = Modifier.padding(11.dp).size(23.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
