package com.sherlock.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onNavigateToVoiceSearch: () -> Unit = {},
    onNavigateToPeopleFinder: () -> Unit = {},
    onNavigateToLicensePlate: () -> Unit = {},
    onNavigateToImageHash: () -> Unit = {},
    onNavigateToUnifiedSearch: () -> Unit = {},
    onNavigateToObjectDetection: () -> Unit = {},
    onNavigateToImageLabeling: () -> Unit = {},
    onNavigateToImageDiff: () -> Unit = {},
    onNavigateToCollage: () -> Unit = {},
    onNavigateToProfileLinkHealth: () -> Unit = {},
    onNavigateToUsernameMatcher: () -> Unit = {},
    onNavigateToPlatformFootprint: () -> Unit = {},
    onNavigateToBioLinkExtractor: () -> Unit = {},
    onNavigateToUsernameFormatValidator: () -> Unit = {},
    onNavigateToPlatformGuide: () -> Unit = {},
    onNavigateToDigitalIdentity: () -> Unit = {},
    onNavigateToSslCertificate: () -> Unit = {},
    onNavigateToDnsRecords: () -> Unit = {},
    onNavigateToHttpHeaders: () -> Unit = {},
    onNavigateToWebsiteSnapshot: () -> Unit = {},
    onNavigateToMyIp: () -> Unit = {},
    onNavigateToRedirectChain: () -> Unit = {},
    onNavigateToVpnProxyCheck: () -> Unit = {},
    onNavigateToHistoryExport: () -> Unit = {},
    onNavigateToFavoritesExport: () -> Unit = {},
    onNavigateToNotesExport: () -> Unit = {},
    onNavigateToProjectReport: () -> Unit = {},
    onNavigateToIdentityReport: () -> Unit = {},
    onNavigateToFullBackupExport: () -> Unit = {},
    onNavigateToSummaryCard: () -> Unit = {},
    onNavigateToQuickSearch: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context) }
    val db = remember { AppDatabase.getInstance(context) }

    val reduceMotion by settings.reduceMotion.collectAsState(initial = false)
    val pinnedKeys by settings.pinnedTiles.collectAsState(initial = emptySet())
    val recentSearches by remember { db.searchHistoryDao().getRecentHistory(8) }.collectAsState(initial = emptyList())

    var showContent by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showPinnedManager by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (reduceMotion) showContent = true else { delay(100); showContent = true }
    }

    val allTiles = remember {
        listOf(
            HomeTile("חיפוש תמונה", "זיהוי פנים וחיפוש", Icons.Default.CameraAlt, onNavigateToFaceSearch),
            HomeTile("שם משתמש", "300+ אתרים", Icons.Default.Person, onNavigateToUsernameSearch),
            HomeTile("חיפוש אימייל", "מצא פרופילים", Icons.Default.Email, onNavigateToEmailSearch),
            HomeTile("מספר טלפון", "WhatsApp, Truecaller", Icons.Default.Phone, onNavigateToPhoneSearch),
            HomeTile("סריקה קבוצתית", "חיפוש מרובה", Icons.Default.PlaylistAddCheck, onNavigateToBatchScanner),
            HomeTile("חיפוש קולי", "דיקטציה למיקרופון", Icons.Default.Mic, onNavigateToVoiceSearch),
            HomeTile("Google Dorking", "חיפוש מתקדם", Icons.Default.ManageSearch, onNavigateToGoogleDork),
            HomeTile("איתור אנשים", "שם+עיר, כתובת, עבודה", Icons.Default.PersonPin, onNavigateToPeopleFinder),
            HomeTile("לוחית רישוי", "מאגר רכב ממשלתי", Icons.Default.DirectionsCar, onNavigateToLicensePlate),
            HomeTile("התאמת תמונות", "השוואה מקומית", Icons.Default.ImageSearch, onNavigateToImageHash),
            HomeTile("חיפוש מאוחד", "כל הסוגים יחד", Icons.Default.JoinFull, onNavigateToUnifiedSearch),
            HomeTile("השוואת פנים", "בדוק התאמה", Icons.Default.Compare, onNavigateToFaceCompare),
            HomeTile("זיהוי טקסט OCR", "חלץ מתמונה", Icons.Default.DocumentScanner, onNavigateToOCR),
            HomeTile("ניתוח פורנזי", "בדיקת עריכה", Icons.Default.ImageSearch, onNavigateToImageForensics),
            HomeTile("ניתוח שם משתמש", "זיהוי תבניות", Icons.Default.Psychology, onNavigateToUsernameAnalysis),
            HomeTile("גלאי פייק", "ציון אמינות", Icons.Default.Shield, onNavigateToFakeProfile),
            HomeTile("תבניות אימייל", "ניחוש כתובות", Icons.Default.AlternateEmail, onNavigateToEmailPattern),
            HomeTile("זיהוי אובייקטים", "רמזים מהרקע", Icons.Default.Category, onNavigateToObjectDetection),
            HomeTile("זיהוי תוויות", "תיוג תמונה כללי", Icons.Default.Label, onNavigateToImageLabeling),
            HomeTile("השוואת תמונות", "מפת הבדלים", Icons.Default.Difference, onNavigateToImageDiff),
            HomeTile("יוצר קולאז'", "עד 9 תמונות", Icons.Default.GridView, onNavigateToCollage),
            HomeTile("בדיקת דומיין", "WHOIS & DNS", Icons.Default.Language, onNavigateToDomainLookup),
            HomeTile("מיקום IP", "Geolocation", Icons.Default.LocationOn, onNavigateToIpGeolocation),
            HomeTile("תתי-דומיינים", "סריקת subdomain", Icons.Default.Dns, onNavigateToSubdomain),
            HomeTile("מידע טלפון", "ניתוח מספר", Icons.Default.PhoneAndroid, onNavigateToPhoneInfo),
            HomeTile("אישור SSL", "תוקף ומנפיק", Icons.Default.Lock, onNavigateToSslCertificate),
            HomeTile("רשומות DNS", "MX, TXT, NS ועוד", Icons.Default.Dns, onNavigateToDnsRecords),
            HomeTile("HTTP Headers", "זיהוי טכנולוגיות", Icons.Default.Http, onNavigateToHttpHeaders),
            HomeTile("תקציר אתר", "כותרת, תיאור ואייקון", Icons.Default.Language, onNavigateToWebsiteSnapshot),
            HomeTile("ה-IP שלי", "מיקום ורשת נוכחית", Icons.Default.Public, onNavigateToMyIp),
            HomeTile("מעקב הפניות", "שרשרת redirects", Icons.Default.AltRoute, onNavigateToRedirectChain),
            HomeTile("זיהוי VPN/Proxy", "בדיקת מקור IP", Icons.Default.Shield, onNavigateToVpnProxyCheck),
            HomeTile("EXIF Viewer", "מטא-דאטה", Icons.Default.Info, onNavigateToExifViewer),
            HomeTile("הסרת מטא-דאטה", "ניקוי EXIF", Icons.Default.CleaningServices, onNavigateToMetadataStripper),
            HomeTile("יוצר QR", "URL, WiFi ועוד", Icons.Default.QrCode2, onNavigateToQRGenerator),
            HomeTile("מפת רשתות", "20 פלטפורמות", Icons.Default.AccountTree, onNavigateToSocialGraph),
            HomeTile("השוואת פרופילים", "זה מול זה", Icons.Default.CompareArrows, onNavigateToSideBySide),
            HomeTile("ניטור פרופילים", "התראות שינויים", Icons.Default.Notifications, onNavigateToMonitor),
            HomeTile("בדיקת קישורים", "תקינות פרופילים", Icons.Default.NetworkCheck, onNavigateToProfileLinkHealth),
            HomeTile("התאמת שמות משתמש", "דמיון בין פלטפורמות", Icons.Default.JoinInner, onNavigateToUsernameMatcher),
            HomeTile("ציון נוכחות רשתית", "פילוח לפי קטגוריה", Icons.Default.Insights, onNavigateToPlatformFootprint),
            HomeTile("חילוץ קישורים", "מתוך ביוגרפיה", Icons.Default.ContentPaste, onNavigateToBioLinkExtractor),
            HomeTile("תקינות שם משתמש", "בדיקה לפי פלטפורמה", Icons.Default.Spellcheck, onNavigateToUsernameFormatValidator),
            HomeTile("מדריך OSINT", "טיפים לפי פלטפורמה", Icons.Default.MenuBook, onNavigateToPlatformGuide),
            HomeTile("כרטיסי זהות", "ריכוז פרופילים לאדם", Icons.Default.Badge, onNavigateToDigitalIdentity),
            HomeTile("פרויקטי חקירה", "ארגון מחקרים", Icons.Default.Folder, onNavigateToProjects),
            HomeTile("הערות", "תיעוד ממצאים", Icons.Default.StickyNote2, onNavigateToNotes),
            HomeTile("תבניות חיפוש", "חיפוש שמור", Icons.Default.SavedSearch, onNavigateToSearchTemplates),
            HomeTile("אתרים מותאמים", "הוסף אתרים", Icons.Default.AddLink, onNavigateToCustomSites),
            HomeTile("ציר זמן", "היסטוריית פעולות", Icons.Default.Timeline, onNavigateToTimeline),
            HomeTile("מועדפים", "פרופילים שמורים", Icons.Default.Star, onNavigateToFavorites),
            HomeTile("סטטיסטיקות", "דשבורד נתונים", Icons.Default.BarChart, onNavigateToStatistics),
            HomeTile("ייצוא היסטוריה", "CSV / HTML", Icons.Default.History, onNavigateToHistoryExport),
            HomeTile("ייצוא מועדפים", "CSV / HTML", Icons.Default.Star, onNavigateToFavoritesExport),
            HomeTile("ייצוא הערות", "קובץ טקסט", Icons.Default.StickyNote2, onNavigateToNotesExport),
            HomeTile("דוח פרויקט", "משימות והערות", Icons.Default.Description, onNavigateToProjectReport),
            HomeTile("דוח כרטיס זהות", "ריכוז פרופילים", Icons.Default.Badge, onNavigateToIdentityReport),
            HomeTile("גיבוי מלא", "כל הנתונים ב-ZIP", Icons.Default.Archive, onNavigateToFullBackupExport),
            HomeTile("כרטיס סיכום", "תמונה לשיתוף", Icons.Default.Image, onNavigateToSummaryCard)
        )
    }

    val filteredTiles = remember(searchQuery, allTiles) {
        if (searchQuery.isBlank()) emptyList()
        else allTiles.filter { it.title.contains(searchQuery, ignoreCase = true) || it.subtitle.contains(searchQuery, ignoreCase = true) }
    }

    val pinnedTiles = remember(pinnedKeys, allTiles) {
        allTiles.filter { it.title in pinnedKeys }
    }

    if (showPinnedManager) {
        AlertDialog(
            onDismissRequest = { showPinnedManager = false },
            title = { Text("ניהול כלים מועדפים") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    allTiles.forEach { tile ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = tile.title in pinnedKeys,
                                onCheckedChange = { scope.launch { settings.togglePinnedTile(tile.title) } }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(tile.title, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPinnedManager = false }) { Text("סגור") } }
        )
    }

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
                    IconButton(onClick = { showPinnedManager = true }) {
                        Icon(Icons.Default.PushPin, contentDescription = "ניהול מועדפים", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("חיפוש כלי...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, null) }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(8.dp))

            if (searchQuery.isNotBlank()) {
                LazyColumnTiles(filteredTiles)
            } else {
                if (pinnedTiles.isNotEmpty()) {
                    Text(
                        "מועדפים",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(pinnedTiles) { tile ->
                            PinnedChip(tile)
                        }
                    }
                }
                if (recentSearches.isNotEmpty()) {
                    Text(
                        "חיפושים אחרונים",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(recentSearches) { history ->
                            AssistChip(
                                onClick = { onNavigateToQuickSearch(history.query, history.searchType.name) },
                                label = { Text(history.query, maxLines = 1) },
                                leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(16.dp)) }
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showContent,
                    enter = fadeIn() + slideInVertically { 40 }
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
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
                item { FeatureTile("איתור אנשים", "שם+עיר, כתובת, עבודה", Icons.Default.PersonPin, onNavigateToPeopleFinder) }
                item { FeatureTile("לוחית רישוי", "מאגר רכב ממשלתי", Icons.Default.DirectionsCar, onNavigateToLicensePlate) }
                item { FeatureTile("התאמת תמונות", "השוואה מקומית", Icons.Default.ImageSearch, onNavigateToImageHash) }
                item { FeatureTile("חיפוש מאוחד", "כל הסוגים יחד", Icons.Default.JoinFull, onNavigateToUnifiedSearch) }

                // Analysis
                item(span = { GridItemSpan(2) }) { SectionHeader("ניתוח וזיהוי") }
                item { FeatureTile("השוואת פנים", "בדוק התאמה", Icons.Default.Compare, onNavigateToFaceCompare) }
                item { FeatureTile("זיהוי טקסט OCR", "חלץ מתמונה", Icons.Default.DocumentScanner, onNavigateToOCR) }
                item { FeatureTile("ניתוח פורנזי", "בדיקת עריכה", Icons.Default.ImageSearch, onNavigateToImageForensics) }
                item { FeatureTile("ניתוח שם משתמש", "זיהוי תבניות", Icons.Default.Psychology, onNavigateToUsernameAnalysis) }
                item { FeatureTile("גלאי פייק", "ציון אמינות", Icons.Default.Shield, onNavigateToFakeProfile) }
                item { FeatureTile("תבניות אימייל", "ניחוש כתובות", Icons.Default.AlternateEmail, onNavigateToEmailPattern) }
                item { FeatureTile("זיהוי אובייקטים", "רמזים מהרקע", Icons.Default.Category, onNavigateToObjectDetection) }
                item { FeatureTile("זיהוי תוויות", "תיוג תמונה כללי", Icons.Default.Label, onNavigateToImageLabeling) }
                item { FeatureTile("השוואת תמונות", "מפת הבדלים", Icons.Default.Difference, onNavigateToImageDiff) }
                item { FeatureTile("יוצר קולאז'", "עד 9 תמונות", Icons.Default.GridView, onNavigateToCollage) }

                // Network
                item(span = { GridItemSpan(2) }) { SectionHeader("רשת ודומיינים") }
                item { FeatureTile("בדיקת דומיין", "WHOIS & DNS", Icons.Default.Language, onNavigateToDomainLookup) }
                item { FeatureTile("מיקום IP", "Geolocation", Icons.Default.LocationOn, onNavigateToIpGeolocation) }
                item { FeatureTile("תתי-דומיינים", "סריקת subdomain", Icons.Default.Dns, onNavigateToSubdomain) }
                item { FeatureTile("מידע טלפון", "ניתוח מספר", Icons.Default.PhoneAndroid, onNavigateToPhoneInfo) }
                item { FeatureTile("אישור SSL", "תוקף ומנפיק", Icons.Default.Lock, onNavigateToSslCertificate) }
                item { FeatureTile("רשומות DNS", "MX, TXT, NS ועוד", Icons.Default.Dns, onNavigateToDnsRecords) }
                item { FeatureTile("HTTP Headers", "זיהוי טכנולוגיות", Icons.Default.Http, onNavigateToHttpHeaders) }
                item { FeatureTile("תקציר אתר", "כותרת, תיאור ואייקון", Icons.Default.Language, onNavigateToWebsiteSnapshot) }
                item { FeatureTile("ה-IP שלי", "מיקום ורשת נוכחית", Icons.Default.Public, onNavigateToMyIp) }
                item { FeatureTile("מעקב הפניות", "שרשרת redirects", Icons.Default.AltRoute, onNavigateToRedirectChain) }
                item { FeatureTile("זיהוי VPN/Proxy", "בדיקת מקור IP", Icons.Default.Shield, onNavigateToVpnProxyCheck) }

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
                item { FeatureTile("בדיקת קישורים", "תקינות פרופילים", Icons.Default.NetworkCheck, onNavigateToProfileLinkHealth) }
                item { FeatureTile("התאמת שמות משתמש", "דמיון בין פלטפורמות", Icons.Default.JoinInner, onNavigateToUsernameMatcher) }
                item { FeatureTile("ציון נוכחות רשתית", "פילוח לפי קטגוריה", Icons.Default.Insights, onNavigateToPlatformFootprint) }
                item { FeatureTile("חילוץ קישורים", "מתוך ביוגרפיה", Icons.Default.ContentPaste, onNavigateToBioLinkExtractor) }
                item { FeatureTile("תקינות שם משתמש", "בדיקה לפי פלטפורמה", Icons.Default.Spellcheck, onNavigateToUsernameFormatValidator) }
                item { FeatureTile("מדריך OSINT", "טיפים לפי פלטפורמה", Icons.Default.MenuBook, onNavigateToPlatformGuide) }
                item { FeatureTile("כרטיסי זהות", "ריכוז פרופילים לאדם", Icons.Default.Badge, onNavigateToDigitalIdentity) }

                // Management
                item(span = { GridItemSpan(2) }) { SectionHeader("ניהול וארגון") }
                item { FeatureTile("פרויקטי חקירה", "ארגון מחקרים", Icons.Default.Folder, onNavigateToProjects) }
                item { FeatureTile("הערות", "תיעוד ממצאים", Icons.Default.StickyNote2, onNavigateToNotes) }
                item { FeatureTile("תבניות חיפוש", "חיפוש שמור", Icons.Default.SavedSearch, onNavigateToSearchTemplates) }
                item { FeatureTile("אתרים מותאמים", "הוסף אתרים", Icons.Default.AddLink, onNavigateToCustomSites) }
                item { FeatureTile("ציר זמן", "היסטוריית פעולות", Icons.Default.Timeline, onNavigateToTimeline) }
                item { FeatureTile("מועדפים", "פרופילים שמורים", Icons.Default.Star, onNavigateToFavorites) }
                item { FeatureTile("סטטיסטיקות", "דשבורד נתונים", Icons.Default.BarChart, onNavigateToStatistics) }

                // Export & Reporting
                item(span = { GridItemSpan(2) }) { SectionHeader("ייצוא, שיתוף ודיווח") }
                item { FeatureTile("ייצוא היסטוריה", "CSV / HTML", Icons.Default.History, onNavigateToHistoryExport) }
                item { FeatureTile("ייצוא מועדפים", "CSV / HTML", Icons.Default.Star, onNavigateToFavoritesExport) }
                item { FeatureTile("ייצוא הערות", "קובץ טקסט", Icons.Default.StickyNote2, onNavigateToNotesExport) }
                item { FeatureTile("דוח פרויקט", "משימות והערות", Icons.Default.Description, onNavigateToProjectReport) }
                item { FeatureTile("דוח כרטיס זהות", "ריכוז פרופילים", Icons.Default.Badge, onNavigateToIdentityReport) }
                item { FeatureTile("גיבוי מלא", "כל הנתונים ב-ZIP", Icons.Default.Archive, onNavigateToFullBackupExport) }
                item { FeatureTile("כרטיס סיכום", "תמונה לשיתוף", Icons.Default.Image, onNavigateToSummaryCard) }
                    }
                }
            }
        }
    }
}

private data class HomeTile(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
private fun LazyColumnTiles(tiles: List<HomeTile>) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (tiles.isEmpty()) {
            item {
                Text(
                    "לא נמצאו תוצאות",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            }
        }
        items(tiles) { tile ->
            Card(
                onClick = tile.onClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(tile.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(tile.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(tile.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PinnedChip(tile: HomeTile) {
    Card(
        onClick = tile.onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(tile.icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(tile.title, fontSize = 13.sp, maxLines = 1)
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
