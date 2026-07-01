package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DISPOSABLE_DOMAINS = setOf(
    "mailinator.com", "guerrillamail.com", "guerrillamail.info", "guerrillamail.org",
    "guerrillamail.net", "guerrillamail.de", "guerrillamailblock.com", "throwam.com",
    "tempmail.com", "temp-mail.org", "dispostable.com", "mailnull.com", "spam4.me",
    "yopmail.com", "yopmail.fr", "cool.fr.nf", "jetable.fr.nf", "nospam.ze.tc",
    "nomail.xl.cx", "mega.zik.dj", "speed.1s.fr", "courriel.fr.nf", "moncourrier.fr.nf",
    "monemail.fr.nf", "monmail.fr.nf", "trashmail.com", "trashmail.me", "trashmail.at",
    "trashmail.io", "trashmail.net", "trashmail.org", "trashmailer.com", "trash-mail.at",
    "discard.email", "discardmail.com", "discardmail.de", "spamgourmet.com",
    "spamgourmet.net", "spamgourmet.org", "getonemail.com", "maildrop.cc",
    "throwam.com", "throwaway.email", "fakeinbox.com", "tempinbox.com",
    "inboxbear.com", "inboxkitten.com", "emailondeck.com", "moakt.com",
    "mailnesia.com", "spamevader.com", "trbvm.com", "burnermail.io",
    "10minutemail.com", "10minutemail.net", "10minutemail.org", "10minemail.com",
    "sharklasers.com", "guerrillamailblock.com", "grr.la", "guerrillamail.info",
    "spam4.me", "spamgourmet.com", "mt2015.com", "mt2014.com", "mt2016.com",
    "rootfest.net", "drdrb.net", "drdrb.com", "smellfear.com", "chammy.info",
    "probemail.com", "trbvm.com", "otherinbox.com", "dispostable.com",
    "brefmail.com", "sneakemail.com", "spikio.com", "filzmail.com",
    "no-spam.ws", "anonbox.net", "nowmymail.com", "hailmail.net", "iroid.com",
    "mailsucker.net", "inbox.si", "freemail.ms", "emailtemporanea.net",
    "tapaz.net", "gowikibooks.com", "gowikicampus.com", "gowikicars.com",
    "gowikifilms.com", "gowikigames.com", "gowikimusic.com", "gowikinetwork.com",
    "gowikitravel.com", "gowikitv.com", "yandex.ua", "e4ward.com",
    "example.com", "test.com", "mailtest.com", "wegwerfmail.de", "spamex.com"
)

private fun detectEmailType(email: String): Triple<Boolean, Boolean, String> {
    val parts = email.trim().lowercase().split("@")
    if (parts.size != 2) return Triple(false, false, "פורמט לא תקין")
    val domain = parts[1]
    val isDisposable = DISPOSABLE_DOMAINS.contains(domain)
    val isCommon = domain in setOf(
        "gmail.com", "yahoo.com", "hotmail.com", "outlook.com", "icloud.com",
        "walla.co.il", "bezeqint.net", "netvision.net.il"
    )
    val verdict = when {
        isDisposable -> "כתובת זמנית/חד-פעמית"
        isCommon -> "ספק מייל נפוץ ואמין"
        else -> "ספק לא ידוע — לא זמני בהכרח"
    }
    return Triple(isDisposable, isCommon, verdict)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TempEmailDetectorScreen(onNavigateBack: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var bulkInput by remember { mutableStateOf("") }
    var showBulk by remember { mutableStateOf(false) }

    data class CheckResult(val email: String, val isDisposable: Boolean, val isCommon: Boolean, val verdict: String)

    val singleResult: CheckResult? = remember(email) {
        if (email.contains("@")) {
            val (disp, common, v) = detectEmailType(email)
            CheckResult(email.trim(), disp, common, v)
        } else null
    }

    val bulkResults: List<CheckResult> = remember(bulkInput) {
        bulkInput.lines()
            .map { it.trim() }
            .filter { it.contains("@") }
            .map { e ->
                val (d, c, v) = detectEmailType(e)
                CheckResult(e, d, c, v)
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("זיהוי מייל זמני") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "בדוק האם כתובת מייל שייכת לספק מייל חד-פעמי/זמני (בדיקה מקומית ללא חיבור לאינטרנט)",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showBulk, onClick = { showBulk = false }, label = { Text("בדיקה בודדת") })
                FilterChip(selected = showBulk, onClick = { showBulk = true }, label = { Text("בדיקה מרוכזת") })
            }

            if (!showBulk) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text("כתובת אימייל") },
                    leadingIcon = { Icon(Icons.Default.Email, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                singleResult?.let { r ->
                    ResultCard(r.email, r.isDisposable, r.isCommon, r.verdict)
                }
            } else {
                OutlinedTextField(
                    value = bulkInput,
                    onValueChange = { bulkInput = it },
                    label = { Text("הדבק רשימת כתובות (שורה לכל כתובת)") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    maxLines = 6
                )
                if (bulkResults.isNotEmpty()) {
                    val disposableCount = bulkResults.count { it.isDisposable }
                    Text("${bulkResults.size} כתובות, $disposableCount זמניות", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(bulkResults) { r ->
                            ResultCard(r.email, r.isDisposable, r.isCommon, r.verdict)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(email: String, isDisposable: Boolean, isCommon: Boolean, verdict: String) {
    val color = when {
        isDisposable -> MaterialTheme.colorScheme.errorContainer
        isCommon -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isDisposable) Icons.Default.Warning else if (isCommon) Icons.Default.CheckCircle else Icons.Default.Info,
                null,
                tint = if (isDisposable) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(email, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text(verdict, fontSize = 12.sp, color = if (isDisposable) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
