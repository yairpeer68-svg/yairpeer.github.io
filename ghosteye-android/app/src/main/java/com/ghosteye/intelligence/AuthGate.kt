package com.ghosteye.intelligence

import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class GateState { Checking, Login, Authenticated }

@Composable
fun AuthGate(baseUrl: String, content: @Composable (onLogout: () -> Unit) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember(baseUrl) { AuthClient(context, baseUrl) }
    var gate by remember { mutableStateOf(GateState.Checking) }

    LaunchedEffect(Unit) {
        gate = try {
            if (auth.ensureSession()) GateState.Authenticated else GateState.Login
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A corrupt local session or temporary startup problem must never
            // terminate the Activity. Fall back to the explicit login screen.
            GateState.Login
        }
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
                    try {
                        when (val result = auth.loginOwner(password)) {
                            LoginResult.Success -> gate = GateState.Authenticated
                            LoginResult.InvalidCredentials -> setError("הסיסמה שגויה")
                            is LoginResult.RateLimited -> setError("יותר מדי ניסיונות שגויים. נסה שוב בעוד ${result.retryAfterSeconds} שניות")
                            is LoginResult.NetworkError -> setError("אין חיבור לשרת. בדוק אינטרנט ונסה שוב")
                            is LoginResult.ServerError -> setError(buildString { append("השרת החזיר שגיאה ${result.code}. נסה שוב בעוד רגע"); result.requestId?.let { append(" • מזהה: ${it.take(12)}") } })
                            LoginResult.StorageError -> setError("ההתחברות הצליחה, אבל שמירת ההתחברות בטלפון נכשלה. הפעל מחדש את האפליקציה")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A platform/keystore edge case must never terminate the Activity.
                        setError("ההתחברות נכשלה בטלפון. נסה שוב או הפעל מחדש את האפליקציה")
                    } finally {
                        setBusy(false)
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
            Image(
                painter = painterResource(R.drawable.ghost_eye_brand),
                contentDescription = "Ghost Eye",
                modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)),
                contentScale = ContentScale.Crop
            )
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
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    SensitiveContentProtection(password.isNotBlank())
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
                Image(
                    painter = painterResource(R.drawable.ghost_eye_brand),
                    contentDescription = "Ghost Eye",
                    modifier = Modifier.size(112.dp).clip(RoundedCornerShape(28.dp)),
                    contentScale = ContentScale.Crop
                )
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
                        Text(ServerConfig.OWNER_LABEL, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
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

                error?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            message,
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
                    "Secure Cloud • HTTPS",
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
