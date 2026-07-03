package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.sherlock.app.data.FaceCheckRepository
import com.sherlock.app.data.FaceMatch
import com.sherlock.app.data.ImageSearchRepository
import kotlinx.coroutines.launch
import java.io.File

private data class Engine(val name: String, val url: String)

private val engines = listOf(
    Engine("Google Lens", "https://lens.google.com"),
    Engine("Yandex", "https://yandex.com/images"),
    Engine("Bing", "https://www.bing.com/visualsearch"),
    Engine("TinEye", "https://tineye.com")
)

@Composable
fun ImageSearchScreen(
    repository: ImageSearchRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val faceRepo = remember { FaceCheckRepository(context) }

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var statusMessage by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    val matches = remember { mutableStateListOf<FaceMatch>() }
    var showSettings by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { selectedImageUri = it; statusMessage = ""; matches.clear() } }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) { selectedImageUri = cameraImageUri; statusMessage = ""; matches.clear() } }

    fun openUrl(url: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    fun launchCamera() {
        try {
            val file = File(context.cacheDir, "camera_capture.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            cameraImageUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            statusMessage = "Camera unavailable: ${e.message ?: "no camera app"}"
        }
    }

    fun runFaceSearch() {
        val uri = selectedImageUri ?: return
        if (searching) return
        if (faceRepo.getToken().isBlank()) { showSettings = true; return }
        matches.clear()
        searching = true
        progress = 0
        statusMessage = "Uploading face to FaceCheck…"
        scope.launch {
            val result = faceRepo.search(uri) { p -> progress = p; statusMessage = "Scanning… $p%" }
            searching = false
            if (result.error != null) {
                statusMessage = "⚠ ${result.error}"
            } else {
                matches.addAll(result.matches)
                statusMessage = if (result.matches.isEmpty()) "No face matches found." else "${result.matches.size} matches found."
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "⊞ FACE RECON",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "MATCH A FACE ACROSS THE WEB",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.Settings, "FaceCheck settings")
                }
            }
        }

        // fixed controls
        Column(Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        2.dp,
                        if (selectedImageUri != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(12.dp)
                    )
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { galleryLauncher.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (selectedImageUri != null) {
                    AsyncImage(
                        model = selectedImageUri, contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Face, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        Text("Tap to load a face", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoLibrary, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Gallery")
                }
                OutlinedButton(onClick = { launchCamera() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Camera")
                }
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { runFaceSearch() },
                enabled = selectedImageUri != null && !searching,
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Icon(Icons.Default.Face, null); Spacer(Modifier.width(8.dp))
                Text(if (searching) "SCANNING… $progress%" else "▶ START — FACE SEARCH", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            if (searching) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth())
            }
            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(statusMessage, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }
        }

        // results + fallback engines
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(matches, key = { it.url }) { m -> FaceMatchCard(m) { openUrl(m.url) } }

            item {
                if (matches.isEmpty()) {
                    Column {
                        Text(
                            "REVERSE-IMAGE ENGINES",
                            fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        engines.forEach { e ->
                            OutlinedButton(
                                onClick = {
                                    if (e.name == "Yandex" && selectedImageUri != null) {
                                        scope.launch {
                                            statusMessage = "Uploading to Yandex…"
                                            val u = repository.uploadToYandex(selectedImageUri!!)
                                            openUrl(u ?: e.url)
                                        }
                                    } else openUrl(e.url)
                                },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            ) { Text(e.name) }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        FaceCheckSettingsDialog(
            initialToken = faceRepo.getToken(),
            initialDemo = faceRepo.isDemo(),
            onDismiss = { showSettings = false },
            onSave = { token, demo ->
                faceRepo.setToken(token); faceRepo.setDemo(demo); showSettings = false
                statusMessage = if (token.isBlank()) "No token set." else "FaceCheck token saved."
            }
        )
    }
}

@Composable
private fun FaceMatchCard(match: FaceMatch, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        val thumbModel = remember(match.thumb) {
            if (match.thumb.isBlank()) null
            else runCatching {
                val b64 = match.thumb.substringAfter(",", match.thumb)
                java.nio.ByteBuffer.wrap(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            }.getOrNull()
        }
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (thumbModel != null) {
                AsyncImage(
                    model = thumbModel, contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "MATCH ${match.score}%",
                    fontWeight = FontWeight.Bold,
                    color = if (match.score >= 70) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                Text(match.url, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

@Composable
private fun FaceCheckSettingsDialog(
    initialToken: String,
    initialDemo: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Boolean) -> Unit
) {
    var token by remember { mutableStateOf(initialToken) }
    var demo by remember { mutableStateOf(initialDemo) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("FaceCheck API") },
        text = {
            Column {
                Text(
                    "Paste your FaceCheck.ID API token. Get one at facecheck.id (paid).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("API token") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = demo, onCheckedChange = { demo = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Demo mode (sample results, no credits)", fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(token, demo) }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
