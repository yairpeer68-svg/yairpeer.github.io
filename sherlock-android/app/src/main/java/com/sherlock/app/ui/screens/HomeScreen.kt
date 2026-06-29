package com.sherlock.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
    onNavigateToExifViewer: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToStatistics: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToMonitor: () -> Unit,
    onNavigateToDomainLookup: () -> Unit,
    onNavigateToOCR: () -> Unit = {},
    onNavigateToImageForensics: () -> Unit = {},
    onNavigateToUsernameAnalysis: () -> Unit = {},
    onNavigateToEmailPattern: () -> Unit = {},
    onNavigateToFakeProfile: () -> Unit = {},
    onNavigateToProjects: () -> Unit = {},
    onNavigateToNotes: () -> Unit = {},
    onNavigateToSearchTemplates: () -> Unit = {},
    onNavigateToQRGenerator: () -> Unit = {},
    onNavigateToIpGeolocation: () -> Unit = {},
    onNavigateToCustomSites: () -> Unit = {},
    onNavigateToTimeline: () -> Unit = {},
    onNavigateToBatchScanner: () -> Unit = {},
    onNavigateToSideBySide: () -> Unit = {},
    onNavigateToSocialGraph: () -> Unit = {},
    onNavigateToPhoneInfo: () -> Unit = {},
    onNavigateToSubdomain: () -> Unit = {},
    onNavigateToMetadataStripper: () -> Unit = {},
    onNavigateToVoiceSearch: () -> Unit = {}
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // Search
                item(span = { GridItemSpan(2) }) { SectionHeader("חיפוש ואיתור") }
                item { FeatureTile("חיפוש תמונה", "זיהוי פנים וחיפוש", Icons.Default.CameraAlt, onNavigateToFaceSearch) }
                item { FeatureTile("שם משתמש", "300+ אתרים", Icons.Default.Person, onNavigateToUsernameSearch) }
                item { FeatureTile("חיפוש אימייל", "מצא פרופילים", Icons.Default.Email, onNavigateToEmailSearch) }
                item { FeatureTile("מספר טלפון", "WhatsApp, Truecaller", Icons.Default.Phone, onNavigateToPhoneSearch) }
                item { FeatureTile("סריקה קבוצתית", "חיפוש מרובה", Icons.Default.PlaylistAddCheck, onNavigateToBatchScanner) }
                item { FeatureTile("חיפוש קולי", "דיקטציה למיקרופון", Icons.Default.Mic, onNavigateToVoiceSearch) }
                item { FeatureTile("Google Dorking", "חיפוש מתקדם", Icons.Default.ManageSearch, onNavigateToGoogleDork) }

                // Analysis
                item(span = { GridItemSpan(2) }) { SectionHeader("ניתוח וזיהוי") }
                item { FeatureTile("השוואת פנים", "בדוק התאמה", Icons.Default.Compare, onNavigateToFaceCompare) }
                item { FeatureTile("זיהוי טקסט OCR", "חלץ מתמונה", Icons.Default.DocumentScanner, onNavigateToOCR) }
                item { FeatureTile("ניתוח פורנזי", "בדיקת עריכה", Icons.Default.ImageSearch, onNavigateToImageForensics) }
                item { FeatureTile("ניתוח שם משתמש", "זיהוי תבניות", Icons.Default.Psychology, onNavigateToUsernameAnalysis) }
                item { FeatureTile("גלאי פייק", "ציון אמינות", Icons.Default.Shield, onNavigateToFakeProfile) }
                item { FeatureTile("תבניות אימייל", "ניחוש כתובות", Icons.Default.AlternateEmail, onNavigateToEmailPattern) }

                // Network
                item(span = { GridItemSpan(2) }) { SectionHeader("רשת ודומיינים") }
                item { FeatureTile("בדיקת דומיין", "WHOIS & DNS", Icons.Default.Language, onNavigateToDomainLookup) }
                item { FeatureTile("מיקום IP", "Geolocation", Icons.Default.LocationOn, onNavigateToIpGeolocation) }
                item { FeatureTile("תתי-דומיינים", "סריקת subdomain", Icons.Default.Dns, onNavigateToSubdomain) }
                item { FeatureTile("מידע טלפון", "ניתוח מספר", Icons.Default.PhoneAndroid, onNavigateToPhoneInfo) }

                // Images & Media
                item(span = { GridItemSpan(2) }) { SectionHeader("תמונות ומדיה") }
                item { FeatureTile("EXIF Viewer", "מטא-דאטה", Icons.Default.Info, onNavigateToExifViewer) }
                item { FeatureTile("הסרת מטא-דאטה", "ניקוי EXIF", Icons.Default.CleaningServices, onNavigateToMetadataStripper) }
                item { FeatureTile("יוצר QR", "URL, WiFi ועוד", Icons.Default.QrCode2, onNavigateToQRGenerator) }

                // Social
                item(span = { GridItemSpan(2) }) { SectionHeader("רשתות חברתיות") }
                item { FeatureTile("מפת רשתות", "20 פלטפורמות", Icons.Default.AccountTree, onNavigateToSocialGraph) }
                item { FeatureTile("השוואת פרופילים", "זה מול זה", Icons.Default.CompareArrows, onNavigateToSideBySide) }
                item { FeatureTile("ניטור פרופילים", "התראות שינויים", Icons.Default.Notifications, onNavigateToMonitor) }

                // Management
                item(span = { GridItemSpan(2) }) { SectionHeader("ניהול וארגון") }
                item { FeatureTile("פרויקטי חקירה", "ארגון מחקרים", Icons.Default.Folder, onNavigateToProjects) }
                item { FeatureTile("הערות", "תיעוד ממצאים", Icons.Default.StickyNote2, onNavigateToNotes) }
                item { FeatureTile("תבניות חיפוש", "חיפוש שמור", Icons.Default.SavedSearch, onNavigateToSearchTemplates) }
                item { FeatureTile("אתרים מותאמים", "הוסף אתרים", Icons.Default.AddLink, onNavigateToCustomSites) }
                item { FeatureTile("ציר זמן", "היסטוריית פעולות", Icons.Default.Timeline, onNavigateToTimeline) }
                item { FeatureTile("מועדפים", "פרופילים שמורים", Icons.Default.Star, onNavigateToFavorites) }
                item { FeatureTile("סטטיסטיקות", "דשבורד נתונים", Icons.Default.BarChart, onNavigateToStatistics) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
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
            .height(110.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
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
                    modifier = Modifier.size(20.dp)
                )
            }
            Column {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
