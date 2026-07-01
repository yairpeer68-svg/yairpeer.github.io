package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.common.model.DownloadConditions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private data class TargetLang(val tag: String, val hebrewName: String)

private val targetLanguages = listOf(
    TargetLang(TranslateLanguage.HEBREW, "עברית"),
    TargetLang(TranslateLanguage.ENGLISH, "אנגלית"),
    TargetLang(TranslateLanguage.ARABIC, "ערבית"),
    TargetLang(TranslateLanguage.RUSSIAN, "רוסית"),
    TargetLang(TranslateLanguage.FRENCH, "צרפתית"),
    TargetLang(TranslateLanguage.SPANISH, "ספרדית"),
    TargetLang(TranslateLanguage.GERMAN, "גרמנית")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateResultsScreen(onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var targetTag by remember { mutableStateOf(TranslateLanguage.HEBREW) }
    var expanded by remember { mutableStateOf(false) }
    var detectedLanguage by remember { mutableStateOf<String?>(null) }
    var translated by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("תרגום תוצאות", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState())
        ) {
            Text("תרגום מקומי על המכשיר (ML Kit) - ללא שליחת טקסט לשרת חיצוני", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = input,
                onValueChange = { input = it; detectedLanguage = null; translated = null; error = null },
                label = { Text("טקסט לתרגום") },
                modifier = Modifier.fillMaxWidth().height(120.dp)
            )

            Spacer(Modifier.height(12.dp))

            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = targetLanguages.first { it.tag == targetTag }.hebrewName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("שפת יעד") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    targetLanguages.forEach { lang ->
                        DropdownMenuItem(text = { Text(lang.hebrewName) }, onClick = { targetTag = lang.tag; expanded = false })
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = {
                    isLoading = true
                    error = null
                    scope.launch {
                        try {
                            val identifier = LanguageIdentification.getClient()
                            val langCode = identifier.identifyLanguage(input).await()
                            val sourceTag = if (langCode == "und") TranslateLanguage.ENGLISH else (TranslateLanguage.fromLanguageTag(langCode) ?: TranslateLanguage.ENGLISH)
                            detectedLanguage = langCode

                            val options = TranslatorOptions.Builder()
                                .setSourceLanguage(sourceTag)
                                .setTargetLanguage(targetTag)
                                .build()
                            val translator = Translation.getClient(options)
                            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                            translated = translator.translate(input).await()
                            translator.close()
                        } catch (e: Exception) {
                            error = e.message ?: "שגיאה בתרגום"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = input.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Default.Translate, null); Spacer(Modifier.width(8.dp)); Text("תרגם")
                }
            }

            Spacer(Modifier.height(16.dp))

            error?.let { Text("שגיאה: $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

            translated?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        detectedLanguage?.let {
                            Text("שפה שזוהתה: $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(result, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}
