package com.nookfolio.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File

class EditorWebView(context: Context) : WebView(context) {
    private var floatingMode: ActionMode? = null

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? {
        val mode = super.startActionMode(callback, type)
        floatingMode = mode
        return mode
    }

    fun hideSelectionToolbar() {
        try {
            floatingMode?.hide(2000L)
        } catch (e: Exception) {
        }
    }
}

class EditorBridge(
    private var file: File,
    private val onMenu: () -> Unit,
    private val onRenamed: (File) -> Unit,
    private val onHideToolbar: () -> Unit
) {
    @JavascriptInterface
    fun load(): String = if (file.exists()) file.readText() else ""

    @JavascriptInterface
    fun save(html: String) {
        file.writeText(html)
    }

    @JavascriptInterface
    fun title(): String = file.nameWithoutExtension

    @JavascriptInterface
    fun rename(newTitle: String): String {
        val n = newTitle.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (n.isEmpty() || n == file.nameWithoutExtension) return file.nameWithoutExtension
        val target = File(file.parentFile, "$n.html")
        if (target.exists()) return file.nameWithoutExtension
        if (file.renameTo(target)) {
            file = target
            Handler(Looper.getMainLooper()).post { onRenamed(target) }
        }
        return file.nameWithoutExtension
    }

    @JavascriptInterface
    fun openMenu() {
        Handler(Looper.getMainLooper()).post { onMenu() }
    }

    @JavascriptInterface
    fun hideSelectionToolbar() {
        Handler(Looper.getMainLooper()).post { onHideToolbar() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EditorScreen(
    file: File,
    onMenu: () -> Unit,
    onRenamed: (File) -> Unit,
    onWeb: (WebView) -> Unit
) {
    val owner = LocalLifecycleOwner.current
    var webRef: WebView? by remember { mutableStateOf(null) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                webRef?.evaluateJavascript("refit()", null)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = EditorWebView(ctx)
                view.settings.javaScriptEnabled = true
                view.addJavascriptInterface(
                    EditorBridge(file, onMenu, onRenamed) { view.hideSelectionToolbar() },
                    "Android"
                )
                view.loadUrl("file:///android_asset/editor.html")
                webRef = view
                onWeb(view)
                view
            }
        )
    }
}
