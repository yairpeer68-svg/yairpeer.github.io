package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.Patterns

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UrlEmailValidatorScreen(onNavigateBack: () -> Unit) {
    var emailInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }

    val isEmailValid = emailInput.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(emailInput).matches()
    val isUrlValid = urlInput.isNotBlank() && Patterns.WEB_URL.matcher(urlInput).matches()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("בדיקת תקינות URL/אימייל", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            Text("בדיקה בזמן אמת של תקינות פורמט - ללא קריאה לרשת", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = emailInput,
                onValueChange = { emailInput = it },
                label = { Text("כתובת אימייל") },
                leadingIcon = { Icon(Icons.Default.Email, null) },
                trailingIcon = {
                    if (emailInput.isNotBlank()) {
                        Icon(
                            if (isEmailValid) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            null,
                            tint = if (isEmailValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (emailInput.isNotBlank()) {
                Text(
                    if (isEmailValid) "פורמט אימייל תקין" else "פורמט אימייל לא תקין",
                    fontSize = 12.sp,
                    color = if (isEmailValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("כתובת URL") },
                leadingIcon = { Icon(Icons.Default.Link, null) },
                trailingIcon = {
                    if (urlInput.isNotBlank()) {
                        Icon(
                            if (isUrlValid) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            null,
                            tint = if (isUrlValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (urlInput.isNotBlank()) {
                Text(
                    if (isUrlValid) "פורמט URL תקין" else "פורמט URL לא תקין",
                    fontSize = 12.sp,
                    color = if (isUrlValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "הבדיקה בודקת פורמט בלבד ואינה מאמתת שהכתובת פעילה באמת",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
