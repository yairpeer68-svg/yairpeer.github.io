package com.sherlock.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.data.repository.UsernameSearchRepository
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

data class BatchResult(val username: String, val found: Int, val total: Int, val isComplete: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScannerScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<BatchResult>>(emptyList()) }
    var isRunning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var totalItems by remember { mutableIntStateOf(0) }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                val names = text.lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .map { it.split(",", ";", "\t").first().trim() }
                    .filter { it.isNotBlank() && !it.equals("username", ignoreCase = true) }
                if (names.isNotEmpty()) {
                    inputText = (inputText.lines().filter { it.isNotBlank() } + names).joinToString("\n")
                }
            } catch (_: Exception) { }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("סריקה קבוצתית") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = inputText, onValueChange = { inputText = it },
                label = { Text("שמות משתמש (אחד בכל שורה)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4, maxLines = 8
            )
            Spacer(Modifier.height(8.dp))
            Text("טיפ: הכנס שם משתמש אחד בכל שורה, או ייבא קובץ CSV/TXT", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { filePickerLauncher.launch("text/*") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRunning
            ) {
                Icon(Icons.Default.UploadFile, null)
                Spacer(Modifier.width(8.dp))
                Text("ייבא רשימה מקובץ CSV / TXT")
            }
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    val usernames = inputText.lines().filter { it.isNotBlank() }.map { it.trim() }
                    if (usernames.isEmpty()) return@Button
                    isRunning = true
                    totalItems = usernames.size
                    currentIndex = 0
                    results = usernames.map { BatchResult(it, 0, 0, false) }

                    scope.launch {
                        val repo = UsernameSearchRepository(context)
                        usernames.forEachIndexed { index, username ->
                            currentIndex = index + 1
                            var found = 0
                            var total = 0
                            repo.search(username, SearchType.USERNAME).collect { result ->
                                total++
                                if (result.exists) found++
                            }
                            results = results.toMutableList().apply {
                                set(index, BatchResult(username, found, total, true))
                            }
                        }
                        isRunning = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = inputText.isNotBlank() && !isRunning
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("התחל סריקה")
            }

            if (isRunning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { currentIndex.toFloat() / totalItems.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("סורק $currentIndex / $totalItems...", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { result ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (result.isComplete) {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(result.username, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                if (result.isComplete) {
                                    Text("${result.found} נמצאו מתוך ${result.total} אתרים", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text("בסריקה...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (result.isComplete) {
                                Text("${result.found}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}
