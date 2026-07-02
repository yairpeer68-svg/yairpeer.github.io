package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sherlock.app.data.ImageSearchRepository
import com.sherlock.app.data.SearchRepository

/** Ad-hoc lookups that don't need a case: quick username sweep and reverse image search. */
@Composable
fun QuickToolsScreen(
    searchRepository: SearchRepository,
    imageRepository: ImageSearchRepository,
    modifier: Modifier = Modifier
) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Username") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Image") })
        }
        when (tab) {
            0 -> UsernameSearchScreen(repository = searchRepository, modifier = Modifier.fillMaxSize())
            1 -> ImageSearchScreen(repository = imageRepository, modifier = Modifier.fillMaxSize())
        }
    }
}
