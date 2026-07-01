package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class DorkTemplate(val label: String, val dork: String)
private data class DorkCategory(val name: String, val templates: List<DorkTemplate>)

private val dorkLibrary = listOf(
    DorkCategory("פרופילים ברשתות חברתיות", listOf(
        DorkTemplate("פרופיל אינסטגרם", "site:instagram.com \"שם\""),
        DorkTemplate("פרופיל פייסבוק", "site:facebook.com \"שם\""),
        DorkTemplate("פרופיל לינקדאין", "site:linkedin.com/in \"שם\""),
        DorkTemplate("חשבון טוויטר/X", "site:twitter.com OR site:x.com \"שם\"")
    )),
    DorkCategory("קבצים ומסמכים חשופים", listOf(
        DorkTemplate("מסמכי PDF", "\"שם\" filetype:pdf"),
        DorkTemplate("גליונות Excel", "\"שם\" filetype:xlsx OR filetype:xls"),
        DorkTemplate("מצגות", "\"שם\" filetype:ppt OR filetype:pptx"),
        DorkTemplate("קבצי טקסט", "\"שם\" filetype:txt")
    )),
    DorkCategory("דפי התחברות וניהול חשופים", listOf(
        DorkTemplate("דפי login חשופים", "inurl:login intitle:\"login\" \"דומיין\""),
        DorkTemplate("פאנל ניהול", "inurl:admin intitle:\"admin\" \"דומיין\""),
        DorkTemplate("ממשקי webcam פתוחים", "intitle:\"webcam\" inurl:view")
    )),
    DorkCategory("מאגרי מידע ותצורות חשופות", listOf(
        DorkTemplate("קבצי env חשופים", "filetype:env \"DB_PASSWORD\""),
        DorkTemplate("קבצי config", "filetype:config \"password\""),
        DorkTemplate("גיבויי SQL חשופים", "filetype:sql \"INSERT INTO\"")
    )),
    DorkCategory("פורומים ודיונים", listOf(
        DorkTemplate("אזכורים בפורומים", "\"שם\" site:reddit.com OR site:quora.com"),
        DorkTemplate("פוסטים ישנים", "\"שם\" site:forum OR inurl:forum")
    )),
    DorkCategory("תמונות ומדיה", listOf(
        DorkTemplate("תמונות בשרתי תמונות", "\"שם\" site:imgur.com OR site:flickr.com"),
        DorkTemplate("סרטוני יוטיוב", "\"שם\" site:youtube.com")
    ))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DorksLibraryScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expandedCategory by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ספריית Google Dorks", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            item {
                Text("ספריית תבניות חיפוש מתקדמות לפי קטגוריה. החליפי \"שם\"/\"דומיין\" ביעד שלך", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
            }

            dorkLibrary.forEach { category ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        onClick = { expandedCategory = if (expandedCategory == category.name) null else category.name }
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(category.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("${category.templates.size} תבניות", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (expandedCategory == category.name) {
                    items(category.templates) { template ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(template.label, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    Text(template.dork, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                }
                                IconButton(onClick = { clipboard.setText(AnnotatedString(template.dork)) }) {
                                    Icon(Icons.Default.ContentCopy, "העתק", Modifier.size(18.dp))
                                }
                                IconButton(onClick = {
                                    val url = "https://www.google.com/search?q=${Uri.encode(template.dork)}"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }) {
                                    Icon(Icons.Default.OpenInNew, "חפש", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
