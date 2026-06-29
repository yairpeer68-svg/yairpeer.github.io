package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.ui.theme.SherlockError
import com.sherlock.app.ui.theme.SherlockSuccess
import com.sherlock.app.ui.theme.SherlockWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreachCheckScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var email by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Breach Check", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("בדוק אם כתובת האימייל או שם המשתמש שלך הודלפו בפריצות אבטחה", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("אימייל או שם משתמש...") },
                    leadingIcon = { Icon(Icons.Default.Security, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
                )

                Spacer(Modifier.height(16.dp))
                Text("בדיקה דרך שירותים חיצוניים:", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
            }

            val services = listOf(
                Triple("Have I Been Pwned", "https://haveibeenpwned.com/", "המאגר הגדול ביותר של דליפות - 13+ מיליארד רשומות"),
                Triple("DeHashed", "https://dehashed.com/", "מנוע חיפוש דליפות מתקדם"),
                Triple("LeakCheck", "https://leakcheck.io/", "בדיקת דליפות בזמן אמת"),
                Triple("Intelligence X", "https://intelx.io/", "חיפוש במאגרי מידע ו-Dark Web"),
                Triple("BreachDirectory", "https://breachdirectory.org/", "ספריית דליפות חינמית"),
                Triple("Snusbase", "https://snusbase.com/", "חיפוש בדליפות מסדי נתונים"),
                Triple("Firefox Monitor", "https://monitor.firefox.com/", "כלי של Mozilla לבדיקת דליפות"),
                Triple("CyberNews Checker", "https://cybernews.com/personal-data-leak-check/", "בדיקת דליפות של CyberNews"),
            )

            services.forEach { (name, url, desc) ->
                item {
                    Card(
                        onClick = {
                            val searchUrl = if (email.isNotEmpty()) "$url?q=${Uri.encode(email)}" else url
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, null, Modifier.size(24.dp), tint = SherlockWarning)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, fontWeight = FontWeight.Medium)
                                Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("טיפים לאבטחה", fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("• השתמש בסיסמאות חזקות וייחודיות לכל אתר", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("• הפעל אימות דו-שלבי (2FA) בכל מקום", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("• החלף סיסמאות שהודלפו מיד", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("• השתמש במנהל סיסמאות", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
