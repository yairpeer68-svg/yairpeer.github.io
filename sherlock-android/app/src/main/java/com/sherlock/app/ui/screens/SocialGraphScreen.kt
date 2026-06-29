package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

data class SocialLink(val platform: String, val url: String, val icon: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialGraphScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var links by remember { mutableStateOf<List<SocialLink>>(emptyList()) }

    val platformTemplates = listOf(
        Triple("Instagram", "https://www.instagram.com/{}/", Icons.Default.CameraAlt),
        Triple("Twitter/X", "https://x.com/{}", Icons.Default.Tag),
        Triple("Facebook", "https://www.facebook.com/{}", Icons.Default.Facebook),
        Triple("TikTok", "https://www.tiktok.com/@{}", Icons.Default.MusicNote),
        Triple("YouTube", "https://www.youtube.com/@{}", Icons.Default.PlayCircle),
        Triple("LinkedIn", "https://www.linkedin.com/in/{}", Icons.Default.Work),
        Triple("GitHub", "https://github.com/{}", Icons.Default.Code),
        Triple("Reddit", "https://www.reddit.com/user/{}", Icons.Default.Forum),
        Triple("Pinterest", "https://www.pinterest.com/{}", Icons.Default.PushPin),
        Triple("Telegram", "https://t.me/{}", Icons.Default.Send),
        Triple("Snapchat", "https://www.snapchat.com/add/{}", Icons.Default.PhotoCamera),
        Triple("Twitch", "https://www.twitch.tv/{}", Icons.Default.Tv),
        Triple("Steam", "https://steamcommunity.com/id/{}", Icons.Default.SportsEsports),
        Triple("Spotify", "https://open.spotify.com/user/{}", Icons.Default.MusicNote),
        Triple("Medium", "https://medium.com/@{}", Icons.Default.Article),
        Triple("DeviantArt", "https://www.deviantart.com/{}", Icons.Default.Palette),
        Triple("Flickr", "https://www.flickr.com/people/{}", Icons.Default.Photo),
        Triple("SoundCloud", "https://soundcloud.com/{}", Icons.Default.Headphones),
        Triple("Vimeo", "https://vimeo.com/{}", Icons.Default.Videocam),
        Triple("Behance", "https://www.behance.net/{}", Icons.Default.DesignServices)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("מפת רשתות חברתיות") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("שם משתמש") },
                leadingIcon = { Icon(Icons.Default.Person, null) },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    links = platformTemplates.map { (name, template, _) ->
                        SocialLink(name, template.replace("{}", username), "")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = username.isNotBlank()
            ) {
                Icon(Icons.Default.AccountTree, null)
                Spacer(Modifier.width(8.dp))
                Text("צור מפת רשתות (${platformTemplates.size} פלטפורמות)")
            }
            Spacer(Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(links.zip(platformTemplates)) { (link, template) ->
                    Card(modifier = Modifier.fillMaxWidth(), onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
                    }) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(template.third, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(link.platform, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(link.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}
