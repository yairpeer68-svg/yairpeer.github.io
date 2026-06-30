package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
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
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.*
import com.sherlock.app.data.repository.ExportRepository
import com.sherlock.app.data.repository.SitesDatabase
import com.sherlock.app.data.repository.UsernameSearchRepository
import com.sherlock.app.ui.components.ResultCard
import com.sherlock.app.ui.components.SkeletonLoader
import com.sherlock.app.ui.components.hapticFeedback
import com.sherlock.app.ui.theme.SherlockSuccess
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.launch

enum class ResultSortOption(val hebrewName: String) {
    NAME("שם אתר"),
    RESPONSE_TIME("זמן תגובה")
}

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
    val exportRepository = remember { ExportRepository(context) }
    val db = remember { AppDatabase.getInstance(context) }
    val settings = remember { SettingsManager(context) }
    val autoFavoriteOnFound by settings.autoFavoriteOnFound.collectAsState(initial = false)
    val autoExportOnComplete by settings.autoExportOnComplete.collectAsState(initial = false)

    var state by remember { mutableStateOf(UsernameSearchState(searchType = searchType, query = initialQuery)) }
    val results = remember { mutableStateListOf<SearchResult>() }
    var showOnlyFound by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf<SiteCategory?>(null) }
    var favorites by remember { mutableStateOf(setOf<String>()) }
    var showExportMenu by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(ResultSortOption.NAME) }
    var showSortMenu by remember { mutableStateOf(false) }

    val foundCount = results.count { it.exists }
    val filteredResults by remember {
        derivedStateOf {
            results
                .filter { if (showOnlyFound) it.exists else true }
                .filter { selectedCategory == null || it.category == selectedCategory }
                .let { list ->
                    when (sortOption) {
                        ResultSortOption.NAME -> list.sortedBy { it.siteName }
                        ResultSortOption.RESPONSE_TIME -> list.sortedBy { it.responseTimeMs }
                    }
                }
        }
    }

    val title = when (searchType) {
        SearchType.USERNAME -> "חיפוש שם משתמש"
        SearchType.EMAIL -> "חיפוש אימייל"
        SearchType.PHONE -> "חיפוש מספר טלפון"
        SearchType.FULL_NAME -> "חיפוש שם מלא"
        else -> "חיפוש"
    }

    val placeholder = when (searchType) {
        SearchType.USERNAME -> "הכנס שם משתמש..."
        SearchType.EMAIL -> "הכנס כתובת אימייל..."
        SearchType.PHONE -> "הכנס מספר טלפון (עם קידומת)..."
        SearchType.FULL_NAME -> "הכנס שם מלא..."
        else -> "חפש..."
    }

    val leadingIcon = when (searchType) {
        SearchType.EMAIL -> Icons.Default.Email
        SearchType.PHONE -> Icons.Default.Phone
        SearchType.FULL_NAME -> Icons.Default.Badge
        else -> Icons.Default.AlternateEmail
    }

    fun startSearch() {
        if (state.query.isNotEmpty() && !state.isSearching) {
            keyboardController?.hide()
            val totalSites = when (searchType) {
                SearchType.EMAIL -> SitesDatabase.emailSites.size
                SearchType.PHONE -> SitesDatabase.phoneSites.size
                else -> SitesDatabase.sites.size
            }
            results.clear()
            state = state.copy(isSearching = true, progress = 0f, totalSites = totalSites, checkedSites = 0)
            scope.launch {
                repository.search(state.query, searchType).collect { result ->
                    if (result.exists) hapticFeedback(context)
                    results.add(result)
                    state = state.copy(
                        checkedSites = state.checkedSites + 1,
                        progress = (state.checkedSites + 1).toFloat() / state.totalSites
                    )
                    if (result.exists && autoFavoriteOnFound && db.favoriteDao().getFavoriteByUrl(result.url) == null) {
                        favorites = favorites + result.url
                        db.favoriteDao().insertFavorite(
                            Favorite(siteName = result.siteName, url = result.url, username = result.username, category = result.category)
                        )
                    }
                }
                state = state.copy(isSearching = false, progress = 1f)

                if (!state.isIncognito) {
                    val historyId = db.searchHistoryDao().insertHistory(
                        SearchHistory(
                            query = state.query,
                            searchType = searchType,
                            totalFound = results.count { it.exists },
                            totalChecked = results.size
                        )
                    )
                    db.searchHistoryDao().insertResults(results.map { it.copy(historyId = historyId) })
                }

                if (autoExportOnComplete && results.isNotEmpty()) {
                    exportRepository.exportToCsv(results, state.query)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") }
                },
                actions = {
                    if (results.isNotEmpty()) {
                        IconButton(onClick = { exportRepository.shareResults(results, state.query) }) {
                            Icon(Icons.Default.Share, "שתף")
                        }
                        Box {
                            IconButton(onClick = { showExportMenu = true }) {
                                Icon(Icons.Default.Download, "ייצוא")
                            }
                            DropdownMenu(expanded = showExportMenu, onDismissRequest = { showExportMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("ייצוא CSV") },
                                    onClick = {
                                        showExportMenu = false
                                        val uri = exportRepository.exportToCsv(results, state.query)
                                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/csv").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        context.startActivity(intent)
                                    },
                                    leadingIcon = { Icon(Icons.Default.TableChart, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("ייצוא HTML") },
                                    onClick = {
                                        showExportMenu = false
                                        val uri = exportRepository.exportToHtml(results, state.query)
                                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/html").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        context.startActivity(intent)
                                    },
                                    leadingIcon = { Icon(Icons.Default.Code, null) }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { state = state.copy(isIncognito = !state.isIncognito) }) {
                        Icon(
                            if (state.isIncognito) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            "מצב סמוי",
                            tint = if (state.isIncognito) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                if (state.isIncognito) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VisibilityOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("מצב סמוי - החיפוש לא יישמר בהיסטוריה", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = state.query,
                    onValueChange = { state = state.copy(query = it.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(placeholder) },
                    leadingIcon = { Icon(leadingIcon, null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty() && !state.isSearching) {
                            IconButton(onClick = { state = state.copy(query = "") }) { Icon(Icons.Default.Clear, "נקה") }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { startSearch() }),
                    enabled = !state.isSearching
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { startSearch() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = state.query.isNotEmpty() && !state.isSearching
                ) {
                    if (state.isSearching) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("מחפש... (${state.checkedSites}/${state.totalSites})")
                    } else {
                        Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("חפש")
                    }
                }

                if (state.isSearching) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                }

                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = SherlockSuccess)
                            Spacer(Modifier.width(4.dp))
                            Text("$foundCount נמצאו מתוך ${results.size}", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, "מיון")
                                }
                                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                    ResultSortOption.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.hebrewName) },
                                            onClick = { sortOption = option; showSortMenu = false },
                                            leadingIcon = {
                                                if (sortOption == option) Icon(Icons.Default.Check, null)
                                            }
                                        )
                                    }
                                }
                            }
                            FilterChip(
                                selected = showOnlyFound,
                                onClick = { showOnlyFound = !showOnlyFound },
                                label = { Text("נמצאו", fontSize = 12.sp) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text("הכל", fontSize = 11.sp) }
                            )
                        }
                        val cats = results.filter { it.exists }.map { it.category }.distinct()
                        items(cats) { cat ->
                            val count = results.count { it.exists && it.category == cat }
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                                label = { Text("${cat.hebrewName} ($count)", fontSize = 11.sp) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.isSearching && results.isEmpty()) {
                    items(5) { SkeletonLoader() }
                }

                items(items = filteredResults, key = { "${it.siteName}_${it.username}" }) { result ->
                    ResultCard(
                        result = result,
                        isFavorite = result.url in favorites,
                        onFavoriteToggle = {
                            scope.launch {
                                if (result.url in favorites) {
                                    favorites = favorites - result.url
                                    val fav = db.favoriteDao().getFavoriteByUrl(result.url)
                                    fav?.let { db.favoriteDao().deleteFavorite(it) }
                                } else {
                                    favorites = favorites + result.url
                                    db.favoriteDao().insertFavorite(
                                        Favorite(siteName = result.siteName, url = result.url, username = result.username, category = result.category)
                                    )
                                }
                            }
                        },
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
                            context.startActivity(intent)
                        }
                    )
                }

                if (!state.isSearching && filteredResults.isEmpty() && results.isNotEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Text("אין תוצאות לסינון הנבחר", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
