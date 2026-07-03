package com.sherlock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.SearchRepository
import com.sherlock.app.data.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsernameSearchScreen(
    repository: SearchRepository,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var username by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<SearchResult>() }
    var isSearching by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var showFoundOnly by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val siteCount = remember { repository.getSiteCount() }
    val filteredResults = if (showFoundOnly) results.filter { it.found } else results.toList()
    val foundCount = results.count { it.found }

    fun startSearch() {
        if (username.isBlank()) return
        searchJob?.cancel()
        results.clear()
        isSearching = true
        progress = 0f

        searchJob = scope.launch {
            try {
                repository.searchUsername(username).collect { result ->
                    results.add(result)
                    progress = results.size.toFloat() / siteCount
                }
            } catch (_: Exception) {
            } finally {
                isSearching = false
                progress = 1f
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "⌖ HANDLE RECON",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "TRACE HANDLE // $siteCount NETWORKS",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = {
                        Text("target handle...", fontFamily = FontFamily.Monospace)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { startSearch() }),
                    trailingIcon = {
                        if (isSearching) {
                            IconButton(onClick = {
                                searchJob?.cancel()
                                isSearching = false
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Stop")
                            }
                        } else {
                            IconButton(onClick = { startSearch() }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(Modifier.height(10.dp))

                Button(
                    onClick = { if (isSearching) { searchJob?.cancel(); isSearching = false } else startSearch() },
                    enabled = isSearching || username.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Icon(
                        if (isSearching) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isSearching) "STOP" else "START — scan $siteCount networks",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                if (isSearching || results.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isSearching) "SCANNING ${(progress * 100).toInt()}%" else "SCAN COMPLETE",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "HITS: $foundCount / ${results.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (results.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                FilterChip(
                    selected = showFoundOnly,
                    onClick = { showFoundOnly = !showFoundOnly },
                    label = { Text("Found only") },
                    leadingIcon = if (showFoundOnly) {
                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    } else null
                )
            }

            LazyColumn {
                items(filteredResults, key = { it.site.name }) { result ->
                    ResultRow(result = result, onClick = {
                        if (result.found) {
                            try { uriHandler.openUri(result.profileUrl) } catch (_: Exception) {}
                        }
                    })
                }
            }
        } else if (!isSearching) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "AWAITING TARGET HANDLE",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(result: SearchResult, onClick: () -> Unit) {
    val bgColor = if (result.found) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (result.found) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (result.found) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.site.name,
                fontWeight = if (result.found) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp
            )
            if (result.found) {
                Text(
                    text = result.profileUrl,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = result.site.category,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}
