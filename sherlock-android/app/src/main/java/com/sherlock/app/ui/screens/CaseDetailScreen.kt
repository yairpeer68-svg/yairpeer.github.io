package com.sherlock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.data.CaseRepository
import com.sherlock.app.data.db.FindingEntity
import com.sherlock.app.data.db.SubjectEntity
import com.sherlock.app.data.db.SubjectType
import com.sherlock.app.util.ReportExporter
import kotlinx.coroutines.launch

private fun iconFor(type: SubjectType): ImageVector = when (type) {
    SubjectType.USERNAME -> Icons.Default.Person
    SubjectType.EMAIL -> Icons.Default.Mail
    SubjectType.PHONE -> Icons.Default.Phone
    SubjectType.DOMAIN -> Icons.Default.Language
    SubjectType.IP -> Icons.Default.Router
    SubjectType.NAME -> Icons.Default.Badge
    SubjectType.IMAGE -> Icons.Default.Image
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseDetailScreen(
    caseId: Long,
    repository: CaseRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val case by repository.observeCase(caseId).collectAsState(initial = null)
    val subjects by repository.observeSubjects(caseId).collectAsState(initial = emptyList())
    val findings by repository.observeFindings(caseId).collectAsState(initial = emptyList())
    val findingsBySubject = findings.groupBy { it.subjectId }

    val progress = remember { mutableStateMapOf<Long, Float>() }

    var addType by remember { mutableStateOf(SubjectType.USERNAME) }
    var addValue by remember { mutableStateOf("") }

    fun runInvestigation(subject: SubjectEntity) {
        if (progress.containsKey(subject.id)) return
        progress[subject.id] = 0f
        scope.launch {
            try {
                repository.investigate(subject) { p -> progress[subject.id] = p }
            } finally {
                progress.remove(subject.id)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(case?.name ?: "Case", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        val c = case ?: return@IconButton
                        scope.launch {
                            val fs = repository.getFindings(caseId)
                            val html = ReportExporter.buildHtml(c, subjects, fs)
                            ReportExporter.share(context, c, html)
                        }
                    }) { Icon(Icons.Default.Description, "Export report") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            // ---- add subject ----
            Column(Modifier.padding(16.dp, 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        modifier = Modifier.width(130.dp)
                    ) {
                        OutlinedTextField(
                            value = addType.label,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Type") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            SubjectType.entries.filter { it != SubjectType.IMAGE }.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.label) },
                                    leadingIcon = { Icon(iconFor(t), null) },
                                    onClick = { addType = t; expanded = false }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = addValue,
                        onValueChange = { addValue = it },
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    onClick = {
                        val v = addValue.trim()
                        if (v.isBlank()) return@FilledTonalButton
                        val t = addType
                        addValue = ""
                        scope.launch {
                            val id = repository.addSubject(caseId, t, v)
                            if (id > 0) {
                                runInvestigation(SubjectEntity(id = id, caseId = caseId, type = t.name, value = v))
                            }
                        }
                    },
                    enabled = addValue.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add & investigate")
                }
            }

            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            if (subjects.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Add a username, email, phone,\ndomain or IP to begin.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(subjects, key = { it.id }) { subject ->
                        SubjectCard(
                            subject = subject,
                            findings = findingsBySubject[subject.id].orEmpty(),
                            progress = progress[subject.id],
                            onInvestigate = { runInvestigation(subject) },
                            onDelete = { scope.launch { repository.deleteSubject(subject.id) } },
                            onOpenUrl = { url ->
                                try { uriHandler.openUri(url) } catch (_: Exception) {}
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubjectCard(
    subject: SubjectEntity,
    findings: List<FindingEntity>,
    progress: Float?,
    onInvestigate: () -> Unit,
    onDelete: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    val derived = subject.origin != "manual"
    val hits = findings.count { it.positive }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    iconFor(subject.subjectType), null,
                    Modifier.size(20.dp),
                    tint = if (derived) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(subject.value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Row {
                        TypeChip(subject.subjectType.label, MaterialTheme.colorScheme.primary)
                        if (derived) {
                            Spacer(Modifier.width(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Link, null,
                                    Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    " ${subject.origin.removePrefix("derived:")}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
                if (progress != null) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onInvestigate) {
                        Icon(Icons.Default.PlayArrow, "Investigate", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                    }
                }
            }

            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            }

            if (findings.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "$hits result${if (hits == 1) "" else "s"}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                findings.filter { it.positive }.forEach { f ->
                    FindingRow(f, onOpenUrl)
                }
            } else if (subject.investigated) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "No results.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun FindingRow(f: FindingEntity, onOpenUrl: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = f.url.isNotBlank()) { if (f.url.isNotBlank()) onOpenUrl(f.url) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            f.source,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.width(88.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(f.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            if (f.detail.isNotBlank()) {
                Text(
                    f.detail, fontSize = 11.sp,
                    color = if (f.url.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun TypeChip(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        label,
        fontSize = 10.sp,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}
