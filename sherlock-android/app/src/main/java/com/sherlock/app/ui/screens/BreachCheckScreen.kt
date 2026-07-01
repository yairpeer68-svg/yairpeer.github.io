package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.model.BreachInfo
import com.sherlock.app.data.repository.OsintToolsRepository
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreachCheckScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { OsintToolsRepository() }
    val settings = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val apiKey by settings.hibpApiKey.collectAsState(initial = "")

    var email by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BreachInfo>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("בדיקת דליפות מידע") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "בדוק האם כתובת אימייל הופיעה בדליפות מידע ידועות (HaveIBeenPwned)",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (apiKey.isBlank()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("יש להגדיר מפתח API של HaveIBeenPwned במסך ההגדרות כדי לבצע בדיקה", fontSize = 12.sp)
                    }
                }
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim() },
                label = { Text("כתובת אימייל") },
                leadingIcon = { Icon(Icons.Default.Email, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    isLoading = true; error = null; results = null
                    scope.launch {
                        try {
                            results = repository.checkBreaches(email, apiKey)
                        } catch (e: Exception) {
                            error = e.message
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = email.isNotBlank() && !isLoading
            ) {
                if (isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                else { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("בדוק") }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

            results?.let { list ->
                if (list.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                            Spacer(Modifier.width(8.dp))
                            Text("לא נמצאו דליפות ידועות עבור כתובת זו", fontWeight = FontWeight.Medium)
                        }
                    }
                } else {
                    Text("נמצאו ${list.size} דליפות:", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list) { breach -> BreachCard(breach) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreachCard(breach: BreachInfo) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text(breach.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            if (breach.domain.isNotBlank()) Text(breach.domain, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (breach.breachDate.isNotBlank()) Text("תאריך דליפה: ${breach.breachDate}", fontSize = 12.sp)
            if (breach.dataClasses.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("מידע שנחשף: ${breach.dataClasses.joinToString(", ")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            if (breach.description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(breach.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
