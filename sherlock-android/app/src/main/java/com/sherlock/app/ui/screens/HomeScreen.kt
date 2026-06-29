package com.sherlock.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onNavigateToFaceSearch: () -> Unit,
    onNavigateToUsernameSearch: () -> Unit,
    onNavigateToEmailSearch: () -> Unit,
    onNavigateToPhoneSearch: () -> Unit,
    onNavigateToGoogleDork: () -> Unit,
    onNavigateToFaceCompare: () -> Unit,
    onNavigateToBreachCheck: () -> Unit,
    onNavigateToExifViewer: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToMonitor: () -> Unit,
    onNavigateToDomainLookup: () -> Unit
) {
    var showContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(100); showContent = true }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Sherlock",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "מצא כל אחד, בכל מקום",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Default.History, contentDescription = "היסטוריה", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "הגדרות", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    ) { padding ->
        AnimatedVisibility(
            visible = showContent,
            enter = fadeIn() + slideInVertically { 40 }
        ) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item { FeatureTile("חיפוש תמונה", "זיהוי פנים וחיפוש", Icons.Default.CameraAlt, onNavigateToFaceSearch) }
                item { FeatureTile("שם משתמש", "300+ אתרים", Icons.Default.Person, onNavigateToUsernameSearch) }
                item { FeatureTile("חיפוש אימייל", "מצא פרופילים", Icons.Default.Email, onNavigateToEmailSearch) }
                item { FeatureTile("מספר טלפון", "WhatsApp, Truecaller", Icons.Default.Phone, onNavigateToPhoneSearch) }
                item { FeatureTile("השוואת פנים", "בדוק התאמה", Icons.Default.Compare, onNavigateToFaceCompare) }
                item { FeatureTile("Google Dorking", "חיפוש מתקדם", Icons.Default.ManageSearch, onNavigateToGoogleDork) }
                item { FeatureTile("EXIF Viewer", "מטא-דאטה של תמונה", Icons.Default.Info, onNavigateToExifViewer) }
                item { FeatureTile("Breach Check", "בדיקת דליפות", Icons.Default.Security, onNavigateToBreachCheck) }
                item { FeatureTile("בדיקת דומיין", "WHOIS & DNS", Icons.Default.Language, onNavigateToDomainLookup) }
                item { FeatureTile("ניטור פרופילים", "התראות שינויים", Icons.Default.Notifications, onNavigateToMonitor) }
                item { FeatureTile("מועדפים", "פרופילים שמורים", Icons.Default.Star, onNavigateToFavorites) }
                item { FeatureTile("סטטיסטיקות", "דשבורד נתונים", Icons.Default.BarChart, onNavigateToStatistics) }
            }
        }
    }
}

@Composable
private fun FeatureTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
