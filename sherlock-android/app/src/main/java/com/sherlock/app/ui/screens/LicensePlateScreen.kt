package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.sherlock.app.data.model.VehicleInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensePlateScreen(onNavigateBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var plate by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<VehicleInfo?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("חיפוש לוחית רישוי") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "חיפוש פרטי רכב ציבוריים (יצרן, דגם, שנה, צבע) ממאגר הרכב הממשלתי הפתוח data.gov.il. לא מציג פרטי בעלים אישיים.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = plate, onValueChange = { plate = it.filter { c -> c.isDigit() } },
                label = { Text("מספר לוחית רישוי") },
                leadingIcon = { Icon(Icons.Default.DirectionsCar, null) },
                placeholder = { Text("12345678") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            Button(
                onClick = {
                    isLoading = true; error = null; result = null
                    scope.launch {
                        try {
                            val vehicle = withContext(Dispatchers.IO) { lookupVehicle(plate.trim()) }
                            if (vehicle == null) error = "לא נמצא רכב עם מספר לוחית זה" else result = vehicle
                        } catch (e: Exception) {
                            error = "שגיאה: ${e.message}"
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = plate.isNotBlank() && !isLoading
            ) {
                Icon(Icons.Default.Search, null)
                Spacer(Modifier.width(8.dp))
                Text("בדוק רכב")
            }

            if (isLoading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            result?.let { v ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("פרטי הרכב", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        InfoLine("מספר רכב", v.plate)
                        v.manufacturer?.let { InfoLine("יצרן", it) }
                        v.model?.let { InfoLine("דגם", it) }
                        v.year?.let { InfoLine("שנת ייצור", it) }
                        v.color?.let { InfoLine("צבע", it) }
                        v.fuelType?.let { InfoLine("סוג דלק", it) }
                        v.ownershipType?.let { InfoLine("סוג בעלות", it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label: ", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 14.sp)
    }
}

private fun lookupVehicle(plate: String): VehicleInfo? {
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    val resourceId = "053cea08-09bc-40ec-8f7a-156f0677aff3"
    val url = "https://data.gov.il/api/3/action/datastore_search?resource_id=$resourceId&q=$plate"
    val request = Request.Builder().url(url).build()
    val response = client.newCall(request).execute()
    val body = response.body?.string() ?: return null
    val json = Gson().fromJson(body, Map::class.java)
    val result = json["result"] as? Map<*, *> ?: return null
    val records = result["records"] as? List<*> ?: return null
    val record = records.firstOrNull() as? Map<*, *> ?: return null

    return VehicleInfo(
        plate = record["mispar_rechev"]?.toString() ?: plate,
        manufacturer = record["tozeret_nm"]?.toString(),
        model = record["kinuy_mishari"]?.toString(),
        year = record["shnat_yitzur"]?.toString(),
        color = record["tzeva_rechev"]?.toString(),
        fuelType = record["sug_delek_nm"]?.toString(),
        ownershipType = record["baalut"]?.toString()
    )
}
