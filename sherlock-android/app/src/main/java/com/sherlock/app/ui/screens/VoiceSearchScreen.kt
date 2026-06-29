package com.sherlock.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.model.SearchType
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSearchScreen(
    onNavigateBack: () -> Unit,
    onSearch: (String, SearchType) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var recognizedText by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isListening = false
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            recognizedText = matches?.firstOrNull() ?: ""
            error = if (recognizedText.isBlank()) "לא הצלחתי להבין, נסה שוב" else ""
        } else {
            error = "החיפוש הקולי בוטל"
        }
    }

    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "דבר עכשיו...")
        }
        error = ""
        isListening = true
        try {
            launcher.launch(intent)
        } catch (e: Exception) {
            isListening = false
            error = "החיפוש הקולי לא נתמך במכשיר זה"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("חיפוש קולי") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(120.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isListening) {
                    CircularProgressIndicator(modifier = Modifier.size(120.dp), strokeWidth = 3.dp)
                }
                IconButton(
                    onClick = { startListening() },
                    modifier = Modifier.size(90.dp)
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicNone,
                        contentDescription = "התחל הקלטה",
                        tint = if (isListening) Color(0xFFFF1744) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = if (isListening) "מקשיב..." else "הקש על המיקרופון ודבר",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (error.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            if (recognizedText.isNotBlank()) {
                Spacer(Modifier.height(24.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("טקסט שזוהה:", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboard.setText(AnnotatedString(recognizedText)) }) {
                                Icon(Icons.Default.ContentCopy, "העתק")
                            }
                        }
                        Text(recognizedText, fontSize = 16.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("חפש כ:", fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Start))
                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { onSearch(recognizedText, SearchType.USERNAME) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Person, null)
                    Spacer(Modifier.width(8.dp))
                    Text("שם משתמש")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onSearch(recognizedText, SearchType.EMAIL) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Email, null)
                    Spacer(Modifier.width(8.dp))
                    Text("אימייל")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onSearch(recognizedText, SearchType.PHONE) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Phone, null)
                    Spacer(Modifier.width(8.dp))
                    Text("מספר טלפון")
                }
            }
        }
    }
}
