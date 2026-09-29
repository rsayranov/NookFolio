package com.nookfolio.app

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

class EditorBridge(private val file: File, private val onMenu: () -> Unit) {
    @JavascriptInterface
    fun load(): String = if (file.exists()) file.readText() else ""

    @JavascriptInterface
    fun save(html: String) {
        file.writeText(html)
    }

    @JavascriptInterface
    fun openMenu() {
        Handler(Looper.getMainLooper()).post { onMenu() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EditorScreen(file: File, onMenu: () -> Unit, onWeb: (WebView) -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    addJavascriptInterface(EditorBridge(file, onMenu), "Android")
                    loadUrl("file:///android_asset/editor.html")
                    onWeb(this)
                }
            }
        )
    }
}
