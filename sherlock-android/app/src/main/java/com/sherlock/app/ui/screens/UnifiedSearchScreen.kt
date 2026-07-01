package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.model.SearchResult
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.data.repository.UsernameSearchRepository
import com.sherlock.app.ui.components.ResultCard
import com.sherlock.app.ui.components.openUrl
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

private val unifiedTypes = listOf(SearchType.USERNAME, SearchType.EMAIL, SearchType.PHONE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedSearchScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selectedTypes by remember { mutableStateOf(setOf(SearchType.USERNAME)) }
    var isRunning by remember { mutableStateOf(false) }
    var resultsByType by remember { mutableStateOf<Map<SearchType, List<SearchResult>>>(emptyMap()) }
    var activeTab by remember { mutableStateOf<SearchType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("חיפוש מאוחד") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("הזן מילת חיפוש אחת והרץ אותה במקביל מול כמה סוגי חיפוש", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = query, onValueChange = { query = it },
                label = { Text("שם משתמש / אימייל / טלפון") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                unifiedTypes.forEach { type ->
                    FilterChip(
                        selected = type in selectedTypes,
                        onClick = {
                            selectedTypes = if (type in selectedTypes) selectedTypes - type else selectedTypes + type
                        },
                        label = { Text(type.hebrewName) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (query.isNotBlank() && selectedTypes.isNotEmpty()) {
                        isRunning = true
                        resultsByType = emptyMap()
                        scope.launch {
                            val repo = UsernameSearchRepository()
                            for (type in selectedTypes) {
                                val results = repo.search(query.trim(), type).toList()
                                resultsByType = resultsByType + (type to results)
                            }
                            activeTab = selectedTypes.firstOrNull()
                            isRunning = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = query.isNotBlank() && selectedTypes.isNotEmpty() && !isRunning
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("הרץ חיפוש מאוחד")
            }

            if (isRunning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (resultsByType.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ScrollableTabRow(selectedTabIndex = unifiedTypes.indexOf(activeTab).coerceAtLeast(0)) {
                    resultsByType.keys.forEach { type ->
                        val found = resultsByType[type]?.count { it.exists } ?: 0
                        Tab(
                            selected = activeTab == type,
                            onClick = { activeTab = type },
                            text = { Text("${type.hebrewName} ($found)") }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))

                val currentResults = resultsByType[activeTab]?.filter { it.exists } ?: emptyList()
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (currentResults.isEmpty()) {
                        item { Text("לא נמצאו תוצאות עבור ${activeTab?.hebrewName ?: ""}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(currentResults) { result ->
                        ResultCard(result = result, onClick = { openUrl(context, result.url) })
                    }
                }
            }
        }
    }
}
