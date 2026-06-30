package com.sherlock.app.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QRGeneratorScreen(onNavigateBack: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedType by remember { mutableStateOf("URL") }
    val types = listOf("URL", "טקסט", "WiFi", "כרטיס ביקור", "אימייל", "טלפון")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("יוצר QR קוד") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("סוג QR", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                types.take(3).forEach { type ->
                    FilterChip(selected = selectedType == type, onClick = { selectedType = type; input = "" }, label = { Text(type, fontSize = 12.sp) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                types.drop(3).forEach { type ->
                    FilterChip(selected = selectedType == type, onClick = { selectedType = type; input = "" }, label = { Text(type, fontSize = 12.sp) })
                }
            }

            val label = when (selectedType) {
                "URL" -> "כתובת URL"
                "WiFi" -> "שם רשת:סיסמה"
                "כרטיס ביקור" -> "שם;טלפון;אימייל"
                "אימייל" -> "כתובת אימייל"
                "טלפון" -> "מספר טלפון"
                else -> "טקסט"
            }

            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
                minLines = if (selectedType == "כרטיס ביקור") 3 else 1
            )

            Button(
                onClick = {
                    val content = when (selectedType) {
                        "URL" -> if (!input.startsWith("http")) "https://$input" else input
                        "WiFi" -> {
                            val parts = input.split(":")
                            "WIFI:S:${parts.getOrElse(0) { "" }};T:WPA;P:${parts.getOrElse(1) { "" }};;"
                        }
                        "כרטיס ביקור" -> {
                            val parts = input.split(";")
                            "BEGIN:VCARD\nVERSION:3.0\nFN:${parts.getOrElse(0) { "" }}\nTEL:${parts.getOrElse(1) { "" }}\nEMAIL:${parts.getOrElse(2) { "" }}\nEND:VCARD"
                        }
                        "אימייל" -> "mailto:$input"
                        "טלפון" -> "tel:$input"
                        else -> input
                    }
                    qrBitmap = generateQRCode(content, 512)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = input.isNotBlank()
            ) {
                Icon(Icons.Default.QrCode2, null)
                Spacer(Modifier.width(8.dp))
                Text("צור QR קוד")
            }

            qrBitmap?.let { bmp ->
                Card(modifier = Modifier.size(280.dp)) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

private fun generateQRCode(content: String, size: Int): Bitmap {
    val writer = QRCodeWriter()
    val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap
}
