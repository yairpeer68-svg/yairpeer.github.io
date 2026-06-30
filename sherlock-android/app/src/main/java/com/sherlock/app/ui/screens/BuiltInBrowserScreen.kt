package com.sherlock.app.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BuiltInBrowserScreen(onNavigateBack: () -> Unit, initialUrl: String = "") {
    var url by remember { mutableStateOf(initialUrl.ifBlank { "https://www.google.com" }) }
    var inputUrl by remember { mutableStateOf(url) }
    var isLoading by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = inputUrl, onValueChange = { inputUrl = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = LocalTextStyle.current.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                        trailingIcon = {
                            IconButton(onClick = {
                                url = if (!inputUrl.startsWith("http")) "https://$inputUrl" else inputUrl
                                webView?.loadUrl(url)
                            }) { Icon(Icons.Default.Search, "עבור") }
                        }
                    )
                },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזור") } },
                actions = {
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, "רענן") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = WebViewClient()
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                isLoading = newProgress < 100
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
