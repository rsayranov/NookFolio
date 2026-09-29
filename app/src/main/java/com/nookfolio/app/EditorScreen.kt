package com.nookfolio.app

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

class EditorBridge(private val file: File) {
    @JavascriptInterface
    fun load(): String = if (file.exists()) file.readText() else ""

    @JavascriptInterface
    fun save(html: String) {
        file.writeText(html)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EditorScreen(file: File, onClose: () -> Unit) {
    var web by remember { mutableStateOf<WebView?>(null) }

    val close: () -> Unit = {
        val w = web
        if (w != null) w.evaluateJavascript("flush()") { onClose() } else onClose()
    }
    BackHandler { close() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = close) { Text("← Назад") }
            Text(file.nameWithoutExtension, style = MaterialTheme.typography.titleMedium)
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(EditorBridge(file), "Android")
                    loadUrl("file:///android_asset/editor.html")
                    web = this
                }
            }
        )
    }
}
