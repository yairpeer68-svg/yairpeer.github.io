package com.sherlock.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.sherlock.app.data.ImageSearchRepository
import com.sherlock.app.data.SearchRepository
import com.sherlock.app.ui.screens.ImageSearchScreen
import com.sherlock.app.ui.screens.UsernameSearchScreen

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val searchRepo = remember { SearchRepository(context) }
    val imageRepo = remember { ImageSearchRepository(context) }
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Username") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Image, contentDescription = null) },
                    label = { Text("Image") }
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> UsernameSearchScreen(
                repository = searchRepo,
                modifier = Modifier.padding(padding)
            )
            1 -> ImageSearchScreen(
                repository = imageRepo,
                modifier = Modifier.padding(padding)
            )
        }
    }
}
