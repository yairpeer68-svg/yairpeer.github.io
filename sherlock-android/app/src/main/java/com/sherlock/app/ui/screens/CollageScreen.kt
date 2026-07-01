package com.sherlock.app.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.sqrt

private const val COLLAGE_SIZE = 900

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollageScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var collageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var collageFile by remember { mutableStateOf<File?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris = uris.take(9)
            collageBitmap = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("יוצר קולאז'") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "בחר מספר תמונות (עד 9) ליצירת קולאז' אחד - שימושי לסיכום חזותי של חקירה.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(onClick = { launcher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.AddPhotoAlternate, null)
                Spacer(Modifier.width(8.dp))
                Text("בחר תמונות")
            }

            if (selectedUris.isNotEmpty()) {
                Text("נבחרו ${selectedUris.size} תמונות", fontSize = 13.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(selectedUris) { uri ->
                        AsyncImage(
                            model = uri, contentDescription = null,
                            modifier = Modifier.size(70.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Button(
                    onClick = {
                        isProcessing = true
                        scope.launch {
                            val (bmp, file) = withContext(Dispatchers.Default) { buildCollage(context, selectedUris) }
                            collageBitmap = bmp
                            collageFile = file
                            isProcessing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing
                ) {
                    Icon(Icons.Default.GridView, null)
                    Spacer(Modifier.width(8.dp))
                    Text("צור קולאז'")
                }
            }

            if (isProcessing) CircularProgressIndicator()

            collageBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "קולאז'",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
                Button(
                    onClick = {
                        val file = collageFile ?: return@Button
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "שתף קולאז'"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("שתף קולאז'")
                }
            }
        }
    }
}

private fun buildCollage(context: Context, uris: List<Uri>): Pair<Bitmap, File> {
    val cols = ceil(sqrt(uris.size.toDouble())).toInt().coerceAtLeast(1)
    val rows = ceil(uris.size.toDouble() / cols).toInt().coerceAtLeast(1)
    val cellSize = COLLAGE_SIZE / cols

    val output = Bitmap.createBitmap(COLLAGE_SIZE, cellSize * rows, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)

    uris.forEachIndexed { index, uri ->
        val input = context.contentResolver.openInputStream(uri)
        val original = BitmapFactory.decodeStream(input)
        input?.close()
        if (original != null) {
            val cropped = cropToSquare(original)
            val scaled = Bitmap.createScaledBitmap(cropped, cellSize, cellSize, true)
            val col = index % cols
            val row = index / cols
            canvas.drawBitmap(scaled, col * cellSize.toFloat(), row * cellSize.toFloat(), null)
            if (cropped != original) original.recycle()
            if (scaled != cropped) cropped.recycle()
        }
    }

    val file = File(context.cacheDir, "collage_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { out -> output.compress(Bitmap.CompressFormat.JPEG, 90, out) }
    return output to file
}

private fun cropToSquare(bitmap: Bitmap): Bitmap {
    val size = minOf(bitmap.width, bitmap.height)
    val x = (bitmap.width - size) / 2
    val y = (bitmap.height - size) / 2
    return Bitmap.createBitmap(bitmap, x, y, size, size)
}
