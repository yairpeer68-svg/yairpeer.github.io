package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.model.SearchResult
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.data.repository.SitesDatabase
import com.sherlock.app.data.repository.UsernameSearchRepository
import com.sherlock.app.ui.components.ResultCard
import com.sherlock.app.ui.components.SkeletonLoader
import com.sherlock.app.ui.components.hapticFeedback
import com.sherlock.app.ui.theme.SherlockSuccess
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsernameSearchScreen(
    onNavigateBack: () -> Unit,
    searchType: SearchType = SearchType.USERNAME,
    initialQuery: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val repository = remember { UsernameSearchRepository() }

    var query by remember { mutableStateOf(initialQuery) }
    var isSearching by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var checkedSites by remember { mutableStateOf(0) }
    var totalSites by remember { mutableStateOf(0) }
    val results = remember { mutableStateListOf<SearchResult>() }
    var showOnlyFound by remember { mutableStateOf(true) }

    val foundCount = results.count { it.exists }
    val filteredResults by remember {
        derivedStateOf {
            results
                .filter { if (showOnlyFound) it.exists else true }
                .sortedBy { it.siteName }
        }
    }

    val title = when (searchType) {
        SearchType.EMAIL -> "חיפוש אימייל"
        else -> "חיפוש שם משתמש"
    }

    val placeholder = when (searchType) {
        SearchType.EMAIL -> "הכנס כתובת אימייל..."
        else -> "הכנס שם משתמש..."
    }

    val leadingIcon = when (searchType) {
        SearchType.EMAIL -> Icons.Default.Email
        else -> Icons.Default.AlternateEmail
    }

    fun startSearch() {
        if (query.isNotEmpty() && !isSearching) {
            keyboardController?.hide()
            val sites = when (searchType) {
                SearchType.EMAIL -> SitesDatabase.emailSites
                else -> SitesDatabase.sites
            }
            results.clear()
            totalSites = sites.size
            checkedSites = 0
            progress = 0f
            isSearching = true
            scope.launch {
                try {
                    repository.search(query, searchType).collect { result ->
                        if (result.exists) hapticFeedback(context)
                        results.add(result)
                        checkedSites++
                        progress = checkedSites.toFloat() / totalSites.coerceAtLeast(1)
                    }
                } catch (_: Exception) {
                } finally {
                    isSearching = false
                    progress = 1f
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder) },
                    leadingIcon = { Icon(leadingIcon, null) },
                    trailingIcon = {
                        if (query.isNotEmpty() && !isSearching) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, "נקה")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { startSearch() }),
                    enabled = !isSearching
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { startSearch() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = query.isNotEmpty() && !isSearching
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("מחפש... ($checkedSites/$totalSites)")
                    } else {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text("חפש")
                    }
                }

                if (isSearching) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }

                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                null,
                                Modifier.size(16.dp),
                                tint = SherlockSuccess
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "$foundCount נמצאו מתוך ${results.size}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        FilterChip(
                            selected = showOnlyFound,
                            onClick = { showOnlyFound = !showOnlyFound },
                            label = { Text("נמצאו בלבד", fontSize = 12.sp) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isSearching && results.isEmpty()) {
                    items(5) { SkeletonLoader() }
                }

                items(
                    items = filteredResults,
                    key = { "${it.siteName}_${it.username}" }
                ) { result ->
                    ResultCard(
                        result = result,
                        isFavorite = false,
                        onFavoriteToggle = {},
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
                            context.startActivity(intent)
                        }
                    )
                }

                if (!isSearching && filteredResults.isEmpty() && results.isNotEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "אין תוצאות לסינון הנבחר",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
