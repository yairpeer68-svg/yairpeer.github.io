package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.Favorite
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val settings = remember { SettingsManager(context) }
    val compactDensity by settings.compactDensity.collectAsState(initial = false)
    val favorites by db.favoriteDao().getAllFavorites().collectAsState(initial = emptyList())
    val tags by db.favoriteDao().getAllTags().collectAsState(initial = emptyList())
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var editingFavorite by remember { mutableStateOf<Favorite?>(null) }
    var newTag by remember { mutableStateOf("") }

    val filtered = if (selectedTag != null) favorites.filter { it.tag == selectedTag } else favorites

    if (showAddTagDialog) {
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = { Text("הוסף תגית") },
            text = {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    placeholder = { Text("שם התגית...") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editingFavorite?.let { fav ->
                        scope.launch { db.favoriteDao().updateFavorite(fav.copy(tag = newTag)) }
                    }
                    showAddTagDialog = false; newTag = ""
                }) { Text("שמור") }
            },
            dismissButton = { TextButton(onClick = { showAddTagDialog = false }) { Text("ביטול") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("מועדפים (${favorites.size})", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                actions = {
                    if (favorites.isNotEmpty()) {
                        IconButton(onClick = {
                            scope.launch { db.favoriteDao().clearAllFavorites() }
                        }) { Icon(Icons.Default.DeleteSweep, "נקה", tint = MaterialTheme.colorScheme.error) }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.StarOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(16.dp))
                    Text("אין מועדפים עדיין", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("לחץ על הכוכב ליד תוצאה כדי לשמור", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (compactDensity) 4.dp else 8.dp)
            ) {
                if (tags.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                FilterChip(
                                    selected = selectedTag == null,
                                    onClick = { selectedTag = null },
                                    label = { Text("הכל", fontSize = 12.sp) }
                                )
                            }
                            items(tags) { tag ->
                                FilterChip(
                                    selected = selectedTag == tag,
                                    onClick = { selectedTag = if (selectedTag == tag) null else tag },
                                    label = { Text(tag, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                }

                items(filtered, key = { it.id }) { fav ->
                    Card(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fav.url))) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(if (compactDensity) 8.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Star, null, Modifier.size(24.dp), tint = Color(0xFFFFD700))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${fav.siteName} - @${fav.username}", fontWeight = FontWeight.Medium)
                                Row {
                                    Text(fav.category.hebrewName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (fav.tag.isNotEmpty()) {
                                        Text(" • ${fav.tag}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            IconButton(onClick = { editingFavorite = fav; showAddTagDialog = true }, Modifier.size(32.dp)) {
                                Icon(Icons.Default.Label, "תגית", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { scope.launch { db.favoriteDao().deleteFavorite(fav) } }, Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, "מחק", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }
        }
    }
}
