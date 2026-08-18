package com.ghosteye.intelligence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            MaterialTheme {
                // The login gate wraps the app: no session -> username/password
                // screen; once logged in -> the normal shell. The server base
                // URL should come from your build config / settings.
                AuthGate(baseUrl = ServerConfig.BASE_URL) {
                    MainShell()
                }
            }
        }
    }
}
