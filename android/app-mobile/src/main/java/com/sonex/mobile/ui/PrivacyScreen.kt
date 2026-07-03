package com.sonex.mobile.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.sonex.mobile.data.Prefs

/** The full privacy policy, rendered in-app (no external browser). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val url = (Prefs.serverUrl(ctx) ?: Prefs.DEFAULT_SERVER).removeSuffix("/") + "/privacy"
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Privacy") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
        )
    }) { pad ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(pad),
            factory = { c ->
                WebView(c).apply {
                    webViewClient = WebViewClient() // keep navigation inside the app
                    settings.javaScriptEnabled = false // policy page is static — keep the sandbox tight
                    loadUrl(url)
                }
            }
        )
    }
}
