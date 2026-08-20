package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ProjectsScreen(baseUrl: String, modifier: Modifier = Modifier, onSessionExpired: () -> Unit) {
    val context = LocalContext.current
    val api = remember(baseUrl) { ApiClient(context, baseUrl) }
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var selected by remember { mutableStateOf<Project?>(null) }
    var cases by remember { mutableStateOf<List<CaseItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showProjectDialog by remember { mutableStateOf(false) }
    var showCaseDialog by remember { mutableStateOf(false) }
    var deleteProjectCandidate by remember { mutableStateOf<Project?>(null) }
    var deleteCaseCandidate by remember { mutableStateOf<CaseItem?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        error = null
        try {
            val boot = api.mobileBootstrap()
            projects = boot.projects
            boot.errors["projects"]?.let { error = "רכיב הפרויקטים בשרת לא זמין כרגע" }
            val current = selected
            if (current != null && boot.projects.none { it.id == current.id }) {
                selected = null
                cases = emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is SessionExpiredException) onSessionExpired()
            else error = e.message ?: "לא ניתן לטעון פרויקטים"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(selected?.id) {
        val p = selected ?: return@LaunchedEffect
        try {
            cases = api.cases(p.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is SessionExpiredException) onSessionExpired()
            else error = e.message ?: "לא ניתן לטעון Cases"
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PageTitle("פרויקטים", "ארגון ניתוחים, Cases וראיות")
                FilledTonalButton(onClick = { showProjectDialog = true }) { Text("חדש") }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { msg -> item { ErrorPanel(msg) { refreshKey++ } } }

        if (projects.isEmpty() && !loading) {
            item {
                SectionCard {
                    Text("אין פרויקטים עדיין", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("צור פרויקט ראשון כדי לקבץ ניתוחים ו־Cases במקום אחד.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showProjectDialog = true }) { Text("צור פרויקט") }
                }
            }
        }

        items(projects, key = { it.id }) { project ->
            Card(
                onClick = { selected = if (selected?.id == project.id) null else project },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected?.id == project.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(project.id.take(12), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { deleteProjectCandidate = project }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "מחק פרויקט", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (selected?.id == project.id) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Cases", fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { showCaseDialog = true }) { Text("הוסף Case") }
                        }
                        if (cases.isEmpty()) {
                            Text("אין Cases בפרויקט הזה.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            cases.forEach { case ->
                                Spacer(Modifier.height(8.dp))
                                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(Modifier.weight(1f)) {
                                            Text(case.title, fontWeight = FontWeight.SemiBold)
                                            case.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        }
                                        IconButton(onClick = { deleteCaseCandidate = case }) {
                                            Icon(Icons.Rounded.Delete, contentDescription = "מחק Case", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showProjectDialog) {
        TextInputDialog(
            title = "פרויקט חדש",
            label = "שם הפרויקט",
            confirmLabel = "צור",
            onDismiss = { showProjectDialog = false },
            onConfirm = { name ->
                showProjectDialog = false
                scope.launch {
                    try {
                        val created = api.createProject(name)
                        projects = listOf(created) + projects
                        selected = created
                        cases = emptyList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is SessionExpiredException) onSessionExpired() else error = e.message ?: "יצירת הפרויקט נכשלה"
                    }
                }
            }
        )
    }

    if (showCaseDialog && selected != null) {
        TextInputDialog(
            title = "Case חדש",
            label = "כותרת",
            confirmLabel = "הוסף",
            onDismiss = { showCaseDialog = false },
            onConfirm = { title ->
                val p = selected ?: return@TextInputDialog
                showCaseDialog = false
                scope.launch {
                    try {
                        val created = api.createCase(p.id, title, null)
                        cases = cases + created
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (e is SessionExpiredException) onSessionExpired() else error = e.message ?: "יצירת Case נכשלה"
                    }
                }
            }
        )
    }

    deleteProjectCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteProjectCandidate = null },
            title = { Text("מחיקת פרויקט") },
            text = { Text("הפרויקט וכל ה־Cases והקישורים שלו יימחקו. הניתוחים עצמם לא יימחקו.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteProjectCandidate = null
                        scope.launch {
                            try {
                                api.deleteProject(candidate.id)
                                if (selected?.id == candidate.id) { selected = null; cases = emptyList() }
                                refreshKey++
                            } catch (e: SessionExpiredException) {
                                onSessionExpired()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "מחיקת הפרויקט נכשלה"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("מחק") }
            },
            dismissButton = { TextButton(onClick = { deleteProjectCandidate = null }) { Text("ביטול") } }
        )
    }

    deleteCaseCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCaseCandidate = null },
            title = { Text("מחיקת Case") },
            text = { Text("ה־Case והקישורים שלו יימחקו לצמיתות.") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteCaseCandidate = null
                        scope.launch {
                            try {
                                api.deleteCase(candidate.id)
                                cases = cases.filterNot { it.id == candidate.id }
                            } catch (e: SessionExpiredException) {
                                onSessionExpired()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "מחיקת ה־Case נכשלה"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("מחק") }
            },
            dismissButton = { TextButton(onClick = { deleteCaseCandidate = null }) { Text("ביטול") } }
        )
    }
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } }
    )
}
