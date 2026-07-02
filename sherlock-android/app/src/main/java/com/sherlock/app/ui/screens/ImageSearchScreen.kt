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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.sherlock.app.data.ImageSearchRepository
import kotlinx.coroutines.launch
import java.io.File

private data class Engine(val name: String, val description: String, val url: String)

private val engines = listOf(
    Engine("Google Lens", "Visual AI by Google", "https://lens.google.com"),
    Engine("Yandex", "Powerful reverse search", "https://yandex.com/images"),
    Engine("TinEye", "Exact image matching", "https://tineye.com"),
    Engine("Bing Visual", "Microsoft visual search", "https://www.bing.com/visualsearch"),
    Engine("SauceNAO", "Anime & manga sources", "https://saucenao.com"),
    Engine("IQDB", "Multi-service search", "https://iqdb.org")
)

@Composable
fun ImageSearchScreen(
    repository: ImageSearchRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { selectedImageUri = it; statusMessage = "" } }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success -> if (success) { selectedImageUri = cameraImageUri; statusMessage = "" } }

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

    fun searchWith(engine: Engine) {
        val uri = selectedImageUri ?: return
        when (engine.name) {
            "Google Lens" -> {
                val sent = runCatching {
                    context.startActivity(Intent(Intent.ACTION_SEND).apply {
                        type = "image/*"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        setPackage("com.google.android.googlequicksearchbox")
                    })
                }.isSuccess
                if (!sent) openUrl(engine.url)
            }
            "Yandex" -> {
                scope.launch {
                    isUploading = true
                    statusMessage = "Uploading to Yandex..."
                    val resultUrl = repository.uploadToYandex(uri)
                    isUploading = false
                    statusMessage = if (resultUrl != null) "Opening results..." else "Opening Yandex..."
                    openUrl(resultUrl ?: engine.url)
                }
            }
            else -> openUrl(engine.url)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "IMAGE SEARCH",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Find where an image appears online",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
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
                        model = selectedImageUri,
                        contentDescription = "Selected image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tap to select image",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Gallery")
                }
                OutlinedButton(onClick = { launchCamera() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Camera")
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { engines.firstOrNull { it.name == "Yandex" }?.let { searchWith(it) } },
                enabled = selectedImageUri != null && !isUploading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Icon(Icons.Default.ImageSearch, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isUploading) "SEARCHING..." else "START — search this photo",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            if (statusMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = statusMessage,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Search Engines",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            val enabled = selectedImageUri != null && !isUploading

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(engines) { engine ->
                    EngineCard(engine = engine, enabled = enabled, onClick = { searchWith(engine) })
                }
            }
        }
    }
}

@Composable
private fun EngineCard(engine: Engine, enabled: Boolean, onClick: () -> Unit) {
    val alpha = if (enabled) 1f else 0.4f
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.ImageSearch,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = engine.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
            )
            Text(
                text = engine.description,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.8f),
                maxLines = 2
            )
        }
    }
}
