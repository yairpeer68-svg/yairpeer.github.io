package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.repository.SitesDatabase

private enum class FinderTab(val label: String) {
    NAME_CITY("שם ועיר"), ADDRESS("כתובת"), WORKPLACE("מקום עבודה")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleFinderScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(FinderTab.NAME_CITY) }

    var name by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var workName by remember { mutableStateOf("") }
    var company by remember { mutableStateOf("") }

    var dorks by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("איתור אנשים מתקדם") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab.ordinal) {
                FinderTab.values().forEach { t ->
                    Tab(selected = tab == t, onClick = { tab = t; dorks = emptyList() }, text = { Text(t.label) })
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    when (tab) {
                        FinderTab.NAME_CITY -> {
                            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("שם מלא") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("עיר") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { dorks = SitesDatabase.buildNameCityDorks(name.trim(), city.trim()) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = name.isNotBlank() && city.isNotBlank()
                            ) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("צור קישורי חיפוש") }
                        }
                        FinderTab.ADDRESS -> {
                            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("כתובת מלאה") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { dorks = SitesDatabase.buildAddressDorks(address.trim()) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = address.isNotBlank()
                            ) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("צור קישורי חיפוש") }
                        }
                        FinderTab.WORKPLACE -> {
                            OutlinedTextField(value = workName, onValueChange = { workName = it }, label = { Text("שם מלא") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = company, onValueChange = { company = it }, label = { Text("מקום עבודה") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { dorks = SitesDatabase.buildWorkplaceDorks(workName.trim(), company.trim()) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = workName.isNotBlank() && company.isNotBlank()
                            ) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("צור קישורי חיפוש") }
                        }
                    }
                }

                if (dorks.isNotEmpty()) {
                    item { Text("קישורי חיפוש (${dorks.size}):", fontWeight = FontWeight.Medium) }
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

                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}
