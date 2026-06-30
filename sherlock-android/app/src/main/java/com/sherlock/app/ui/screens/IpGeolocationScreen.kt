package com.sherlock.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.sherlock.app.data.model.IpGeoResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IpGeolocationScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ipAddress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<IpGeoResult?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("מיקום IP") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = ipAddress, onValueChange = { ipAddress = it },
                label = { Text("כתובת IP") },
                leadingIcon = { Icon(Icons.Default.Router, null) },
                placeholder = { Text("8.8.8.8") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )

            Button(
                onClick = {
                    isLoading = true; error = null; result = null
                    scope.launch {
                        try {
                            result = withContext(Dispatchers.IO) { lookupIp(ipAddress.trim()) }
                        } catch (e: Exception) {
                            error = "שגיאה: ${e.message}"
                        } finally { isLoading = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = ipAddress.isNotBlank() && !isLoading
            ) {
                Icon(Icons.Default.LocationOn, null)
                Spacer(Modifier.width(8.dp))
                Text("אתר מיקום")
            }

            if (isLoading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            result?.let { r ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("פרטי IP", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        GeoInfoRow("IP", r.ip)
                        GeoInfoRow("מדינה", r.country)
                        GeoInfoRow("עיר", r.city)
                        GeoInfoRow("אזור", r.region)
                        GeoInfoRow("ספק אינטרנט", r.isp)
                        GeoInfoRow("אזור זמן", r.timezone)
                        GeoInfoRow("קואורדינטות", "${r.lat}, ${r.lon}")
                    }
                }

                Button(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps?q=${r.lat},${r.lon}")))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Map, null)
                    Spacer(Modifier.width(8.dp))
                    Text("פתח במפות Google")
                }
            }
        }
    }
}

@Composable
private fun GeoInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label: ", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.width(110.dp))
        Text(value, fontSize = 14.sp)
    }
}

private fun lookupIp(ip: String): IpGeoResult {
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    val request = Request.Builder().url("http://ip-api.com/json/$ip").build()
    val response = client.newCall(request).execute()
    val body = response.body?.string() ?: throw Exception("Empty response")
    val json = Gson().fromJson(body, Map::class.java)
    return IpGeoResult(
        ip = json["query"]?.toString() ?: ip,
        country = json["country"]?.toString() ?: "Unknown",
        city = json["city"]?.toString() ?: "Unknown",
        region = json["regionName"]?.toString() ?: "Unknown",
        isp = json["isp"]?.toString() ?: "Unknown",
        lat = (json["lat"] as? Double) ?: 0.0,
        lon = (json["lon"] as? Double) ?: 0.0,
        timezone = json["timezone"]?.toString() ?: "Unknown"
    )
}
