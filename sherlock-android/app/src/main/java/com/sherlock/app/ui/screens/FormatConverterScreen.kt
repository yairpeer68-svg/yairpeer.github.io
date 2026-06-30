package com.sherlock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Node
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

private enum class DataFormat(val label: String) { JSON("JSON"), CSV("CSV"), XML("XML") }

private fun prettyJson(text: String): Result<String> = try {
    val trimmed = text.trim()
    val pretty = if (trimmed.startsWith("[")) JSONArray(trimmed).toString(2) else JSONObject(trimmed).toString(2)
    Result.success(pretty)
} catch (e: Exception) {
    Result.failure(e)
}

private fun prettyXml(text: String): Result<String> = try {
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    val doc = builder.parse(InputSource(StringReader(text.trim())))
    doc.normalize()
    removeWhitespaceNodes(doc.documentElement)
    val transformer = TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(OutputKeys.INDENT, "yes")
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    val writer = StringWriter()
    transformer.transform(DOMSource(doc), StreamResult(writer))
    Result.success(writer.toString())
} catch (e: Exception) {
    Result.failure(e)
}

private fun removeWhitespaceNodes(node: Node) {
    var child = node.firstChild
    while (child != null) {
        val next = child.nextSibling
        if (child.nodeType == Node.TEXT_NODE && child.textContent.isBlank()) {
            node.removeChild(child)
        } else if (child.hasChildNodes()) {
            removeWhitespaceNodes(child)
        }
        child = next
    }
}

private fun csvToJson(text: String): Result<String> = try {
    val lines = text.trim().lines().filter { it.isNotBlank() }
    require(lines.size >= 1) { "אין נתונים" }
    val headers = lines.first().split(",").map { it.trim() }
    val array = JSONArray()
    lines.drop(1).forEach { line ->
        val cells = line.split(",")
        val obj = JSONObject()
        headers.forEachIndexed { i, h -> obj.put(h, cells.getOrNull(i)?.trim() ?: "") }
        array.put(obj)
    }
    Result.success(array.toString(2))
} catch (e: Exception) {
    Result.failure(e)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatConverterScreen(onNavigateBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var selectedFormat by remember { mutableStateOf(DataFormat.JSON) }
    var input by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ממיר פורמטים", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                DataFormat.entries.forEachIndexed { index, format ->
                    SegmentedButton(
                        selected = selectedFormat == format,
                        onClick = { selectedFormat = format; output = null; error = null },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = DataFormat.entries.size)
                    ) { Text(format.label) }
                }
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text(if (selectedFormat == DataFormat.CSV) "הדבק CSV (שורה ראשונה = כותרות)" else "הדבק ${selectedFormat.label} גולמי") },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions.Default
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    val result = when (selectedFormat) {
                        DataFormat.JSON -> prettyJson(input)
                        DataFormat.XML -> prettyXml(input)
                        DataFormat.CSV -> csvToJson(input)
                    }
                    result.onSuccess { output = it; error = null }
                        .onFailure { output = null; error = it.message ?: "שגיאת פענוח" }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = input.isNotBlank()
            ) {
                Text(if (selectedFormat == DataFormat.CSV) "המר ל-JSON" else "עצב ויפה")
            }

            Spacer(Modifier.height(16.dp))

            error?.let {
                Text("שגיאה: $it", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            output?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("תוצאה", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { clipboard.setText(AnnotatedString(result)) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.ContentCopy, "העתק", Modifier.size(18.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(result, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
