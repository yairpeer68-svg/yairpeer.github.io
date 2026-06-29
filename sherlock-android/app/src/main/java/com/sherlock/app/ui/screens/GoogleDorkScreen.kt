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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.repository.SitesDatabase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoogleDorkScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    var dorks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var customDork by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Dorking", fontWeight = FontWeight.Bold) },
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
                Text("חיפוש מתקדם בגוגל עם אופרטורים מיוחדים", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("שם / אימייל / מספר טלפון...") },
                    leadingIcon = { Icon(Icons.Default.ManageSearch, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboardController?.hide()
                        if (query.isNotEmpty()) dorks = SitesDatabase.buildGoogleDorks(query)
                    })
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        keyboardController?.hide()
                        if (query.isNotEmpty()) dorks = SitesDatabase.buildGoogleDorks(query)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = query.isNotEmpty()
                ) {
                    Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("צור שאילתות חיפוש")
                }
            }

            if (dorks.isNotEmpty()) {
                item {
                    Text("שאילתות מוכנות (${dorks.size}):", fontWeight = FontWeight.Medium)
                }

                items(dorks) { (label, dork) ->
                    Card(
                        onClick = {
                            val url = "https://www.google.com/search?q=${Uri.encode(dork)}"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.TravelExplore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(label, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(dork, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text("שאילתה מותאמת אישית:", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customDork,
                    onValueChange = { customDork = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("site:instagram.com \"username\"") },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (customDork.isNotEmpty()) {
                            val url = "https://www.google.com/search?q=${Uri.encode(customDork)}"
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    enabled = customDork.isNotEmpty()
                ) {
                    Text("חפש בגוגל")
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
