package com.sherlock.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sherlock.app.data.model.OcrResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private enum class OcrLanguage(val label: String) {
    LATIN("לטינית / עברית"), CHINESE("סינית"), JAPANESE("יפנית"), KOREAN("קוריאנית"), DEVANAGARI("הינדי")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OCRScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var ocrResult by remember { mutableStateOf<OcrResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var language by remember { mutableStateOf(OcrLanguage.LATIN) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            imageUri = it
            isProcessing = true
            error = null
            scope.launch {
                try {
                    val image = InputImage.fromFilePath(context, it)
                    val recognizer = when (language) {
                        OcrLanguage.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        OcrLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
                        OcrLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                        OcrLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                        OcrLanguage.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
                    }
                    val result = recognizer.process(image).await()
                    val fullText = result.text
                    val usernames = Regex("@[a-zA-Z0-9_.]+").findAll(fullText).map { m -> m.value }.toList()
                    val emails = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").findAll(fullText).map { m -> m.value }.toList()
                    val phones = Regex("(\\+?\\d[\\d\\s-]{7,15})").findAll(fullText).map { m -> m.value.trim() }.toList()
                    val urls = Regex("https?://[\\S]+").findAll(fullText).map { m -> m.value }.toList()
                    ocrResult = OcrResult(fullText, usernames, emails, phones, urls)
                } catch (e: Exception) {
                    error = "שגיאה בזיהוי טקסט: ${e.message}"
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("זיהוי טקסט (OCR)") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("שפת הטקסט בתמונה:", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OcrLanguage.values().forEach { lang ->
                    FilterChip(selected = language == lang, onClick = { language = lang }, label = { Text(lang.label, fontSize = 12.sp) })
                }
            }

            Button(onClick = { launcher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Image, null)
                Spacer(Modifier.width(8.dp))
                Text("בחר תמונה")
            }

            if (isProcessing) {
                CircularProgressIndicator()
                Text("מנתח טקסט...")
            }

            error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }

            ocrResult?.let { result ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("טקסט שזוהה", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboard.setText(AnnotatedString(result.fullText)) }) {
                                Icon(Icons.Default.ContentCopy, "העתק")
                            }
                        }
                        Text(result.fullText.ifEmpty { "לא זוהה טקסט" }, fontSize = 14.sp)
                    }
                }

                if (result.detectedUsernames.isNotEmpty()) {
                    ExtractedSection("שמות משתמש", Icons.Default.Person, result.detectedUsernames)
                }
                if (result.detectedEmails.isNotEmpty()) {
                    ExtractedSection("כתובות אימייל", Icons.Default.Email, result.detectedEmails)
                }
                if (result.detectedPhones.isNotEmpty()) {
                    ExtractedSection("מספרי טלפון", Icons.Default.Phone, result.detectedPhones)
                }
                if (result.detectedUrls.isNotEmpty()) {
                    ExtractedSection("קישורים", Icons.Default.Link, result.detectedUrls)
                }
            }
        }
    }
}

@Composable
private fun ExtractedSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, items: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            items.forEach { item ->
                Text("• $item", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}
