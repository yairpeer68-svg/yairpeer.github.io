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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.launch

private data class WorkflowStep(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvestigationWorkflowScreen(
    onNavigateBack: () -> Unit,
    onNavigateToUsernameSearch: () -> Unit,
    onNavigateToEmailSearch: () -> Unit,
    onNavigateToFaceSearch: () -> Unit,
    onNavigateToDomainLookup: () -> Unit,
    onNavigateToSocialGraph: () -> Unit,
    onNavigateToIdentityReport: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context) }
    val completedSteps by settings.workflowCompletedSteps.collectAsState(initial = emptySet())

    val steps = remember {
        listOf(
            WorkflowStep("username", "חיפוש שם משתמש", "אתר את היעד ברשתות החברתיות", Icons.Default.AlternateEmail, onNavigateToUsernameSearch),
            WorkflowStep("email", "חיפוש אימייל", "בדוק היכן נרשמה כתובת האימייל", Icons.Default.Email, onNavigateToEmailSearch),
            WorkflowStep("face", "השוואת תמונות פנים", "אמת זהות באמצעות תמונה", Icons.Default.Face, onNavigateToFaceSearch),
            WorkflowStep("domain", "בדיקת דומיין/אתר", "בדוק בעלות ופרטי תשתית", Icons.Default.Language, onNavigateToDomainLookup),
            WorkflowStep("graph", "מיפוי גרף חברתי", "חבר בין הפרופילים שנמצאו", Icons.Default.Hub, onNavigateToSocialGraph),
            WorkflowStep("report", "הפקת דוח מסכם", "ייצא את כל הממצאים לדוח אחד", Icons.Default.Summarize, onNavigateToIdentityReport)
        )
    }

    val progress = if (steps.isEmpty()) 0f else completedSteps.size.toFloat() / steps.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("אשף חקירה מודרך", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה") } },
                actions = {
                    IconButton(onClick = { scope.launch { settings.resetWorkflowSteps() } }) {
                        Icon(Icons.Default.RestartAlt, "איפוס")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("התקדמות: ${completedSteps.size}/${steps.size} שלבים", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(steps, key = { it.id }) { step ->
                    val done = step.id in completedSteps
                    Card(
                        onClick = step.onClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(step.icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(step.title, fontWeight = FontWeight.Medium)
                                Text(step.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(
                                checked = done,
                                onCheckedChange = { scope.launch { settings.toggleWorkflowStep(step.id) } }
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
