package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneInfoScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var phoneNumber by remember { mutableStateOf("") }
    var analyzed by remember { mutableStateOf(false) }

    val countryPrefixes = mapOf(
        "+972" to "ישראל", "+1" to "ארה\"ב/קנדה", "+44" to "בריטניה",
        "+49" to "גרמניה", "+33" to "צרפת", "+7" to "רוסיה",
        "+81" to "יפן", "+86" to "סין", "+91" to "הודו",
        "+971" to "איחוד האמירויות", "+966" to "ערב הסעודית",
        "+39" to "איטליה", "+34" to "ספרד", "+61" to "אוסטרליה"
    )

    val lookupServices = listOf(
        "Truecaller" to "https://www.truecaller.com/search/",
        "CallerID" to "https://calleridtest.com/",
        "NumLookup" to "https://www.numlookup.com/",
        "Sync.me" to "https://sync.me/search/?number=",
        "WhitePages" to "https://www.whitepages.com/phone/",
        "SpyDialer" to "https://www.spydialer.com/",
        "PhoneInfoga" to "https://sundowndev.github.io/phoneinfoga/"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("מידע מספר טלפון") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = phoneNumber, onValueChange = { phoneNumber = it },
                label = { Text("מספר טלפון (עם קידומת)") },
                leadingIcon = { Icon(Icons.Default.Phone, null) },
                placeholder = { Text("+972501234567") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            Button(
                onClick = { analyzed = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = phoneNumber.isNotBlank()
            ) {
                Icon(Icons.Default.Analytics, null)
                Spacer(Modifier.width(8.dp))
                Text("נתח מספר")
            }

            if (analyzed && phoneNumber.isNotBlank()) {
                val detectedCountry = countryPrefixes.entries.firstOrNull { phoneNumber.startsWith(it.key) }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ניתוח מספר", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (detectedCountry != null) {
                            PhoneInfoRow("מדינה", detectedCountry.value)
                            PhoneInfoRow("קידומת", detectedCountry.key)
                        }
                        PhoneInfoRow("מספר", phoneNumber)
                        PhoneInfoRow("אורך", "${phoneNumber.filter { it.isDigit() }.length} ספרות")

                        val isIsraeli = phoneNumber.startsWith("+972") || phoneNumber.startsWith("05")
                        if (isIsraeli) {
                            val local = phoneNumber.removePrefix("+972").removePrefix("0")
                            val carrier = when {
                                local.startsWith("50") -> "Pelephone / HOT Mobile"
                                local.startsWith("52") -> "Cellcom"
                                local.startsWith("53") -> "HOT Mobile"
                                local.startsWith("54") -> "Partner"
                                local.startsWith("55") -> "Rami Levy / Golan"
                                local.startsWith("58") -> "Golan Telecom"
                                else -> "לא ידוע"
                            }
                            PhoneInfoRow("ספק (משוער)", carrier)
                        }
                    }
                }

                Text("חפש בשירותים חיצוניים", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                lookupServices.forEach { (name, url) ->
                    OutlinedButton(
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$url${Uri.encode(phoneNumber)}"))) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(name)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label: ", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.width(100.dp))
        Text(value, fontSize = 14.sp)
    }
}
