package com.ghosteye.intelligence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class GateState { Checking, Login, Authenticated }

@Composable
fun AuthGate(baseUrl: String, content: @Composable (onLogout: () -> Unit) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember(baseUrl) { AuthClient(context, baseUrl) }
    var gate by remember { mutableStateOf(GateState.Checking) }

    LaunchedEffect(Unit) {
        gate = if (auth.ensureSession()) GateState.Authenticated else GateState.Login
    }

    when (gate) {
        GateState.Checking -> SecureSplash()
        GateState.Authenticated -> content {
            scope.launch {
                auth.logout()
                gate = GateState.Login
            }
        }
        GateState.Login -> LoginScreen(
            baseUrl = baseUrl,
            onLogin = { password, setBusy, setError ->
                scope.launch {
                    setBusy(true)
                    setError(null)
                    val result = auth.login(ServerConfig.OWNER_EMAIL, password)
                    setBusy(false)
                    when (result) {
                        LoginResult.Success -> gate = GateState.Authenticated
                        LoginResult.InvalidCredentials -> setError("הסיסמה שגויה")
                        is LoginResult.RateLimited -> setError("יותר מדי ניסיונות שגויים. נסה שוב בעוד ${result.retryAfterSeconds} שניות")
                        is LoginResult.NetworkError -> setError("אין חיבור לשרת. בדוק אינטרנט ונסה שוב")
                        is LoginResult.ServerError -> setError("השרת החזיר שגיאה ${result.code}. נסה שוב בעוד רגע")
                        LoginResult.StorageError -> setError("ההתחברות הצליחה, אבל שמירת ההתחברות בטלפון נכשלה. הפעל מחדש את האפליקציה")
                    }
                }
            }
        )
    }
}

@Composable
private fun SecureSplash() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("GE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("מאמת חיבור מאובטח…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LoginScreen(
    baseUrl: String,
    onLogin: (
        password: String,
        setBusy: (Boolean) -> Unit,
        setError: (String?) -> Unit
    ) -> Unit
) {
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy) return
        error = null
        if (password.isBlank()) {
            error = "יש להזין סיסמה"
            return
        }
        onLogin(password, { busy = it }, { error = it })
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(76.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("GE", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("Ghost Eye", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "גישה פרטית ומאובטחת",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(26.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("חשבון מורשה", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(ServerConfig.OWNER_EMAIL, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        if (error != null) error = null
                    },
                    label = { Text("סיסמה") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }, enabled = !busy) {
                            Text(if (showPassword) "הסתר" else "הצג")
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth()
                )

                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            error!!,
                            Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { submit() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("מתחבר…")
                    } else {
                        Text("התחברות", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "שרת מאובטח • ${baseUrl.removePrefix("https://")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "אין הרשמה ואין חשבונות נוספים",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
