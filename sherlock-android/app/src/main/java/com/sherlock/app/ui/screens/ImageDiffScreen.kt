package com.sherlock.app.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val DIFF_SIZE = 200

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageDiffScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri1 by remember { mutableStateOf<Uri?>(null) }
    var uri2 by remember { mutableStateOf<Uri?>(null) }
    var diffBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var diffPercent by remember { mutableStateOf(-1f) }
    var isProcessing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val launcher1 = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri1 = uri; diffBitmap = null; diffPercent = -1f }
    val launcher2 = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri2 = uri; diffBitmap = null; diffPercent = -1f }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("השוואת תמונות לאורך זמן") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "השוואה ויזואלית בין שתי תמונות (לדוגמה: תמונת פרופיל ישנה מול חדשה) - מציג מפת הבדלים בין התמונות.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ImagePicker("תמונה ישנה", uri1) { launcher1.launch("image/*") }
                Icon(Icons.Default.CompareArrows, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                ImagePicker("תמונה חדשה", uri2) { launcher2.launch("image/*") }
            }

            Button(
                onClick = {
                    val u1 = uri1; val u2 = uri2
                    if (u1 != null && u2 != null) {
                        isProcessing = true
                        error = null
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.Default) { computeDiff(context, u1, u2) }
                                diffBitmap = result.first
                                diffPercent = result.second
                            } catch (e: Exception) {
                                error = "שגיאה בהשוואה: ${e.message}"
                            } finally {
                                isProcessing = false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = uri1 != null && uri2 != null && !isProcessing
            ) {
                Icon(Icons.Default.Difference, null)
                Spacer(Modifier.width(8.dp))
                Text("השווה הבדלים")
            }

            if (isProcessing) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (diffPercent >= 0) {
                Text("שיעור הבדל בין התמונות: ${"%.1f".format(diffPercent)}%", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }

            diffBitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "מפת הבדלים",
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
                Text("אזורים אדומים מסמנים הבדלים בין התמונות", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ImagePicker(label: String, uri: Uri?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Card(onClick = onClick, shape = CircleShape, modifier = Modifier.size(100.dp)) {
            if (uri != null) {
                AsyncImage(model = uri, contentDescription = label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.AddAPhoto, null, Modifier.size(32.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun computeDiff(context: Context, uri1: Uri, uri2: Uri): Pair<Bitmap, Float> {
    val bmp1 = decodeScaled(context, uri1)
    val bmp2 = decodeScaled(context, uri2)
    val diff = Bitmap.createBitmap(DIFF_SIZE, DIFF_SIZE, Bitmap.Config.ARGB_8888)

    var diffPixels = 0
    for (y in 0 until DIFF_SIZE) {
        for (x in 0 until DIFF_SIZE) {
            val p1 = bmp1.getPixel(x, y)
            val p2 = bmp2.getPixel(x, y)
            val dr = abs(AndroidColor.red(p1) - AndroidColor.red(p2))
            val dg = abs(AndroidColor.green(p1) - AndroidColor.green(p2))
            val db = abs(AndroidColor.blue(p1) - AndroidColor.blue(p2))
            val delta = (dr + dg + db) / 3
            if (delta > 30) {
                diffPixels++
                diff.setPixel(x, y, AndroidColor.argb(180, 255, 0, 0))
            } else {
                val gray = AndroidColor.red(p1) / 3 + AndroidColor.green(p1) / 3 + AndroidColor.blue(p1) / 3
                diff.setPixel(x, y, AndroidColor.argb(255, gray, gray, gray))
            }
        }
    }
    bmp1.recycle()
    bmp2.recycle()

    val percent = diffPixels.toFloat() * 100f / (DIFF_SIZE * DIFF_SIZE)
    return diff to percent
}

private fun decodeScaled(context: Context, uri: Uri): Bitmap {
    val input = context.contentResolver.openInputStream(uri)
    val original = BitmapFactory.decodeStream(input)
    input?.close()
    val scaled = Bitmap.createScaledBitmap(original, DIFF_SIZE, DIFF_SIZE, true)
    if (original != scaled) original.recycle()
    return scaled
}
