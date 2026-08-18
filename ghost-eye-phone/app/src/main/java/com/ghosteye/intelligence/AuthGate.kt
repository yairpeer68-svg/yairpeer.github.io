package com.ghosteye.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The gate the user sees when the app opens.
 *
 * From the user's point of view this is "type a username and password and
 * you're in" — there is no token to think about. Under the hood, AuthClient
 * exchanges the credentials for a short-lived access token plus a refresh token
 * and stores them encrypted via SessionStore, so the raw password is sent once
 * (over the network to /auth/login) and never persisted on the device.
 *
 * Wrap the whole app in this: if a valid session already exists the gate is
 * skipped and [content] renders immediately.
 */
@Composable
fun AuthGate(baseUrl: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { AuthClient(context, baseUrl) }
    val session = remember { SessionStore(context) }

    // Already logged in? Skip the gate.
    var loggedIn by remember { mutableStateOf(session.access() != null) }

    if (loggedIn) {
        content()
        return
    }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        error = null
        if (email.isBlank() || password.isBlank()) {
            error = "יש להזין שם משתמש וסיסמה"
            return
        }
        if (isRegister && password.length < 10) {
            error = "הסיסמה חייבת להכיל לפחות 10 תווים"
            return
        }
        busy = true
        scope.launch {
            val ok = try {
                if (isRegister) auth.register(email.trim(), password)
                else auth.login(email.trim(), password)
            } catch (e: Exception) {
                false
            }
            busy = false
            if (ok) {
                password = ""          // never keep the raw password around
                loggedIn = true
            } else {
                error = if (isRegister) "ההרשמה נכשלה — ייתכן שהחשבון כבר קיים"
                        else "שם משתמש או סיסמה שגויים"
            }
        }
    }

    Surface {
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Universal Intelligence", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (isRegister) "יצירת חשבון" else "התחברות",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("שם משתמש (אימייל)") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("סיסמה") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error!!, color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = { submit() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text(if (isRegister) "הרשמה" else "התחברות")
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { isRegister = !isRegister; error = null }, enabled = !busy) {
                Text(if (isRegister) "כבר יש לי חשבון — התחברות" else "אין לי חשבון — הרשמה")
            }
        }
    }
}
