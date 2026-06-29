package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.model.SearchResult
import com.sherlock.app.data.model.SiteCategory
import com.sherlock.app.data.model.UsernameSearchState
import com.sherlock.app.data.repository.SitesDatabase
import com.sherlock.app.data.repository.UsernameSearchRepository
import com.sherlock.app.ui.theme.SherlockError
import com.sherlock.app.ui.theme.SherlockSuccess
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsernameSearchScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val repository = remember { UsernameSearchRepository() }

    var state by remember { mutableStateOf(UsernameSearchState()) }
    var showOnlyFound by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf<SiteCategory?>(null) }

    val foundCount = state.results.count { it.exists }
    val filteredResults = state.results
        .filter { if (showOnlyFound) it.exists else true }
        .filter { selectedCategory == null || it.category == selectedCategory }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("חיפוש שם משתמש", fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { state = state.copy(username = it.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("הכנס שם משתמש...") },
                    leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
                    trailingIcon = {
                        if (state.username.isNotEmpty() && !state.isSearching) {
                            IconButton(onClick = { state = state.copy(username = "") }) {
                                Icon(Icons.Default.Clear, contentDescription = "נקה")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (state.username.isNotEmpty() && !state.isSearching) {
                                keyboardController?.hide()
                                state = state.copy(
                                    isSearching = true,
                                    results = emptyList(),
                                    progress = 0f,
                                    totalSites = SitesDatabase.sites.size,
                                    checkedSites = 0
                                )
                                scope.launch {
                                    repository.searchUsername(state.username).collect { result ->
                                        state = state.copy(
                                            results = state.results + result,
                                            checkedSites = state.checkedSites + 1,
                                            progress = (state.checkedSites + 1).toFloat() / state.totalSites
                                        )
                                    }
                                    state = state.copy(isSearching = false, progress = 1f)
                                }
                            }
                        }
                    ),
                    enabled = !state.isSearching
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (state.username.isNotEmpty()) {
                            keyboardController?.hide()
                            state = state.copy(
                                isSearching = true,
                                results = emptyList(),
                                progress = 0f,
                                totalSites = SitesDatabase.sites.size,
                                checkedSites = 0
                            )
                            scope.launch {
                                repository.searchUsername(state.username).collect { result ->
                                    state = state.copy(
                                        results = state.results + result,
                                        checkedSites = state.checkedSites + 1,
                                        progress = (state.checkedSites + 1).toFloat() / state.totalSites
                                    )
                                }
                                state = state.copy(isSearching = false, progress = 1f)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = state.username.isNotEmpty() && !state.isSearching
                ) {
                    if (state.isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("מחפש... (${state.checkedSites}/${state.totalSites})")
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("חפש")
                    }
                }

                if (state.isSearching || state.results.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))

                    if (state.isSearching) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                        )
                    }

                    if (state.results.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "נמצאו $foundCount מתוך ${state.results.size}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row {
                                FilterChip(
                                    selected = showOnlyFound,
                                    onClick = { showOnlyFound = !showOnlyFound },
                                    label = { Text("נמצאו בלבד", fontSize = 12.sp) },
                                    leadingIcon = if (showOnlyFound) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                    } else null
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val categories = listOf<SiteCategory?>(null) + SiteCategory.entries
                            categories.forEachIndexed { index, category ->
                                val count = state.results.count {
                                    it.exists && (category == null || it.category == category)
                                }
                                if (category == null || count > 0) {
                                    SegmentedButton(
                                        selected = selectedCategory == category,
                                        onClick = { selectedCategory = category },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = if (category == null) 0 else categories.indexOf(category),
                                            count = categories.count { cat ->
                                                cat == null || state.results.any { it.exists && it.category == cat }
                                            }
                                        )
                                    ) {
                                        Text(
                                            text = if (category == null) "הכל ($count)" else "${category.hebrewName} ($count)",
                                            fontSize = 10.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = filteredResults,
                    key = { it.siteName }
                ) { result ->
                    ResultCard(result = result) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
                        context.startActivity(intent)
                    }
                }

                if (filteredResults.isEmpty() && state.results.isNotEmpty() && !state.isSearching) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (showOnlyFound) "לא נמצאו תוצאות" else "אין תוצאות לקטגוריה זו",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun ResultCard(result: SearchResult, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (result.exists)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (result.exists)
                            Brush.linearGradient(
                                listOf(
                                    SherlockSuccess.copy(alpha = 0.2f),
                                    SherlockSuccess.copy(alpha = 0.1f)
                                )
                            )
                        else
                            Brush.linearGradient(
                                listOf(
                                    SherlockError.copy(alpha = 0.15f),
                                    SherlockError.copy(alpha = 0.05f)
                                )
                            )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (result.exists) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null,
                    tint = if (result.exists) SherlockSuccess else SherlockError.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.siteName,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = result.category.hebrewName,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (result.exists) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = "פתח",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = "${result.responseTimeMs}ms",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
