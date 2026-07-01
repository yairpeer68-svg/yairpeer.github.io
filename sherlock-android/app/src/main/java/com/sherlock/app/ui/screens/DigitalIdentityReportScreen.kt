package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.DigitalIdentity
import com.sherlock.app.data.model.IdentityLink
import com.sherlock.app.data.repository.ExportRepository
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigitalIdentityReportScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getInstance(context) }
    val repository = remember { ExportRepository(context) }
    val identities by db.digitalIdentityDao().getAll().collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<DigitalIdentity?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val links by remember(selected) { selected?.let { db.identityLinkDao().getForIdentity(it.id) } ?: flowOf(emptyList<IdentityLink>()) }
        .collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("דוח כרטיס זהות") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("בחר כרטיס זהות לייצוא דוח מרוכז של כל הפרופילים המקושרים", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selected?.name ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("כרטיס זהות") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    identities.forEach { identity ->
                        DropdownMenuItem(
                            text = { Text(identity.name) },
                            onClick = { selected = identity; expanded = false }
                        )
                    }
                }
            }

            selected?.let { identity ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("${links.size} פרופילים מקושרים", fontSize = 14.sp)
                    }
                }
                Button(
                    onClick = {
                        val uri = repository.exportIdentityReport(identity, links)
                        repository.shareFile(uri, "text/html", "Sherlock Identity Report - ${identity.name}")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Description, null); Spacer(Modifier.width(8.dp)); Text("ייצוא דוח HTML")
                }
            }
        }
    }
}
