package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class SlangTerm(val term: String, val meaning: String)

private val slangTerms = listOf(
    SlangTerm("DM", "הודעה פרטית (Direct Message)"),
    SlangTerm("FOMO", "פחד מהחמצה (Fear Of Missing Out)"),
    SlangTerm("TBH", "בכנות (To Be Honest)"),
    SlangTerm("IMO / IMHO", "לדעתי (In My (Honest) Opinion)"),
    SlangTerm("FYI", "לידיעתך (For Your Information)"),
    SlangTerm("BRB", "תכף חוזר (Be Right Back)"),
    SlangTerm("AFK", "לא ליד המקלדת (Away From Keyboard)"),
    SlangTerm("RT", "שיתוף/ציוץ חוזר (Retweet)"),
    SlangTerm("OOTD", "תלבושת היום (Outfit Of The Day)"),
    SlangTerm("GRWM", "התכוננות מולי (Get Ready With Me)"),
    SlangTerm("NSFW", "לא בטוח לצפייה בעבודה (Not Safe For Work)"),
    SlangTerm("PFP", "תמונת פרופיל (Profile Picture)"),
    SlangTerm("alt", "חשבון משני (Alt Account)"),
    SlangTerm("finsta", "חשבון אינסטגרם פרטי/משני (Fake Instagram)"),
    SlangTerm("ghosting", "היעלמות פתאומית מקשר תקשורת"),
    SlangTerm("lurker", "מי שעוקב/קורא מבלי להגיב או לפרסם"),
    SlangTerm("throwaway", "חשבון חד פעמי/אנונימי"),
    SlangTerm("DP", "תמונת פרופיל (Display Picture)"),
    SlangTerm("BIO", "תיאור קצר בפרופיל"),
    SlangTerm("handle", "שם המשתמש ברשת חברתית"),
    SlangTerm("OG", "מקורי/ותיק (Original)"),
    SlangTerm("IRL", "במציאות (In Real Life)"),
    SlangTerm("DOX / doxxing", "חשיפת מידע מזהה על אדם ברשת"),
    SlangTerm("catfishing", "התחזות לאדם אחר באמצעות פרופיל מזויף"),
    SlangTerm("sock puppet", "חשבון מזויף המשמש להטעיה"),
    SlangTerm("brigading", "התקפה מתואמת של קבוצת משתמשים"),
    SlangTerm("shadowban", "חסימת חשיפה סמויה ללא הודעה למשתמש"),
    SlangTerm("burner", "מכשיר/חשבון זמני ואנונימי"),
    SlangTerm("OSINT", "מודיעין ממקורות גלויים (Open Source Intelligence)"),
    SlangTerm("PII", "מידע מזהה אישית (Personally Identifiable Information)")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlangDictionaryScreen(onNavigateBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) slangTerms
        else slangTerms.filter { it.term.contains(query, ignoreCase = true) || it.meaning.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("מילון סלנג ברשתות חברתיות", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("חיפוש מונח...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Icon(Icons.Default.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("לא נמצאו מונחים", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(filtered) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(entry.term, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(2.dp))
                                Text(entry.meaning, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
