package com.sherlock.app.ui.screens

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.sherlock.app.data.local.AppDatabase
import com.sherlock.app.data.model.AccessLogEntry
import com.sherlock.app.util.SettingsManager
import kotlinx.coroutines.launch

private fun showBiometricPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle("אימות זהות")
        .setSubtitle("אמת את זהותך כדי להמשיך")
        .setNegativeButtonText("השתמש בקוד PIN")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .build()
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
        }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            onError(errString.toString())
        }
    })
    prompt.authenticate(promptInfo)
}

@Composable
fun AppLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsManager(context) }
    val db = remember { AppDatabase.getInstance(context) }
    var pin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var biometricAttempted by remember { mutableStateOf(false) }

    fun logAccess(success: Boolean, method: String) {
        scope.launch { db.accessLogDao().insert(AccessLogEntry(success = success, method = method)) }
    }

    val biometricAvailable = remember {
        activity != null && BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }

    LaunchedEffect(Unit) {
        if (biometricAvailable && activity != null && !biometricAttempted) {
            biometricAttempted = true
            showBiometricPrompt(
                activity = activity,
                onSuccess = { logAccess(true, "ביומטריה"); onUnlocked() },
                onError = { logAccess(false, "ביומטריה") }
            )
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Lock, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("האפליקציה נעולה", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 8) { pin = it; errorMessage = null } },
                label = { Text("קוד PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                isError = errorMessage != null,
                supportingText = { errorMessage?.let { Text(it) } }
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        if (pin.isNotEmpty() && settings.verifyAppLockPin(pin)) {
                            logAccess(true, "PIN")
                            onUnlocked()
                        } else {
                            logAccess(false, "PIN")
                            errorMessage = "קוד שגוי"
                            pin = ""
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("אישור")
            }

            if (biometricAvailable) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = {
                    activity?.let {
                        showBiometricPrompt(
                            it,
                            onSuccess = { logAccess(true, "ביומטריה"); onUnlocked() },
                            onError = { msg -> logAccess(false, "ביומטריה"); errorMessage = msg }
                        )
                    }
                }) {
                    Icon(Icons.Default.Fingerprint, null)
                    Spacer(Modifier.width(8.dp))
                    Text("נסה שוב עם ביומטריה")
                }
            }
        }
    }
}
