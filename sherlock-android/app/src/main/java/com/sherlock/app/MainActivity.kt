package com.sherlock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sherlock.app.ui.MainScreen
import com.sherlock.app.ui.theme.SherlockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SherlockTheme {
                MainScreen()
            }
        }
    }
}
