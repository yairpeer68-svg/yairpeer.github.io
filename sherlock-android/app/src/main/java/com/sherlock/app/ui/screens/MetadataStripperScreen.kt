package com.sherlock.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataStripperScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { selectedUri = it; resultMessage = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("הסרת מטא-דאטה") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.CleaningServices, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("הסרת מטא-דאטה מתמונות", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("הסר EXIF, GPS, ומידע מזהה מתמונות", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Button(onClick = { launcher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Image, null)
                Spacer(Modifier.width(8.dp))
                Text("בחר תמונה")
            }

            selectedUri?.let { uri ->
                AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp))

                Button(
                    onClick = {
                        isProcessing = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val inputStream = context.contentResolver.openInputStream(uri)
                                    val bitmap = BitmapFactory.decodeStream(inputStream)
                                    inputStream?.close()

                                    val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                                    val outputFile = File(outputDir, "clean_${System.currentTimeMillis()}.jpg")
                                    val fos = FileOutputStream(outputFile)
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                                    fos.close()
                                    bitmap.recycle()
                                }
                                isSuccess = true
                                resultMessage = "התמונה נוקתה ונשמרה בהצלחה!"
                            } catch (e: Exception) {
                                isSuccess = false
                                resultMessage = "שגיאה: ${e.message}"
                            } finally {
                                isProcessing = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing
                ) {
                    Icon(Icons.Default.CleaningServices, null)
                    Spacer(Modifier.width(8.dp))
                    Text("הסר מטא-דאטה ושמור")
                }

                if (isProcessing) {
                    CircularProgressIndicator()
                    Text("מנקה מטא-דאטה...")
                }
            }

            resultMessage?.let { msg ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSuccess) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                            null,
                            tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(msg, fontSize = 14.sp)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("מה מוסר?", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    listOf("מיקום GPS", "מודל מצלמה", "תאריך צילום", "תוכנת עריכה", "הגדרות מצלמה", "תמונה ממוזערת מוטמעת").forEach { item ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(item, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
