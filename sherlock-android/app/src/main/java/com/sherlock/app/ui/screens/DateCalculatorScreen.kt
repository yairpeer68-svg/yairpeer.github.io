package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateCalculatorScreen(onNavigateBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("מחשבון תאריכים", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("חישוב גיל") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("הפרש בין תאריכים") })
            }
            Spacer(Modifier.height(20.dp))
            if (tab == 0) AgeCalculator() else DateDiffCalculator()
        }
    }
}

@Composable
private fun DateField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= 10) onValueChange(it) },
        label = { Text(label) },
        placeholder = { Text("DD/MM/YYYY") },
        leadingIcon = { Icon(Icons.Default.CalendarMonth, null) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun parseDate(text: String): LocalDate? {
    return try {
        LocalDate.parse(text, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun AgeCalculator() {
    var birthDate by remember { mutableStateOf("") }
    val parsed = parseDate(birthDate)
    val today = LocalDate.now()

    Column {
        DateField("תאריך לידה", birthDate) { birthDate = it }
        Spacer(Modifier.height(16.dp))

        if (parsed != null && !parsed.isAfter(today)) {
            val period = Period.between(parsed, today)
            val totalDays = ChronoUnit.DAYS.between(parsed, today)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${period.years} שנים, ${period.months} חודשים, ${period.days} ימים", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("סך הכל $totalDays ימים", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (birthDate.length == 10) {
            Text("תאריך לא תקין", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DateDiffCalculator() {
    var dateA by remember { mutableStateOf("") }
    var dateB by remember { mutableStateOf("") }
    val parsedA = parseDate(dateA)
    val parsedB = parseDate(dateB)

    Column {
        DateField("תאריך ראשון", dateA) { dateA = it }
        Spacer(Modifier.height(12.dp))
        DateField("תאריך שני", dateB) { dateB = it }
        Spacer(Modifier.height(16.dp))

        if (parsedA != null && parsedB != null) {
            val days = ChronoUnit.DAYS.between(parsedA, parsedB)
            val absPeriod = Period.between(if (parsedA.isBefore(parsedB)) parsedA else parsedB, if (parsedA.isBefore(parsedB)) parsedB else parsedA)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${kotlin.math.abs(days)} ימים", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("(${absPeriod.years} שנים, ${absPeriod.months} חודשים, ${absPeriod.days} ימים)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else if (dateA.length == 10 || dateB.length == 10) {
            Text("יש להזין שני תאריכים תקינים", color = MaterialTheme.colorScheme.error)
        }
    }
}
