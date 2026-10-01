package com.nookfolio.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.view.ActionMode
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val CHUNK_BYTES = 3 * 1024 * 1024
private const val CHUNK_CHARS = 1_000_000

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
    private val context: Context,
    private var file: File,
    private val onMenu: () -> Unit,
    private val onRenamed: (File) -> Unit,
    private val onHideToolbar: () -> Unit,
    private val onInsert: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val uris = ConcurrentHashMap<Int, Uri>()
    private val streams = ConcurrentHashMap<Int, InputStream>()
    private val counter = AtomicInteger(0)
    private var bounds: List<IntArray> = emptyList()
    private var loadBuf: String = ""
    private var writer: OutputStreamWriter? = null
    private var tmpFile: File? = null

    fun register(uri: Uri): Int {
        val id = counter.incrementAndGet()
        uris[id] = uri
        return id
    }

    @JavascriptInterface
    fun load(): String = if (file.exists()) file.readText() else ""

    @JavascriptInterface
    fun loadStart(): Int {
        loadBuf = if (file.exists()) file.readText() else ""
        val list = ArrayList<IntArray>()
        var start = 0
        while (start < loadBuf.length) {
            var end = minOf(start + CHUNK_CHARS, loadBuf.length)
            if (end < loadBuf.length && Character.isHighSurrogate(loadBuf[end - 1])) end--
            list.add(intArrayOf(start, end))
            start = end
        }
        bounds = list
        return list.size
    }

    @JavascriptInterface
    fun loadChunk(i: Int): String {
        if (i < 0 || i >= bounds.size) return ""
        return loadBuf.substring(bounds[i][0], bounds[i][1])
    }

    @JavascriptInterface
    fun loadEnd() {
        loadBuf = ""
        bounds = emptyList()
    }

    @JavascriptInterface
    fun save(html: String) {
        file.writeText(html)
    }

    @JavascriptInterface
    fun saveBegin() {
        try {
            writer?.close()
        } catch (e: Exception) {
        }
        val t = File(file.path + ".tmp")
        tmpFile = t
        writer = OutputStreamWriter(FileOutputStream(t), Charsets.UTF_8)
    }

    @JavascriptInterface
    fun saveChunk(part: String) {
        writer?.write(part)
    }

    @JavascriptInterface
    fun saveEnd() {
        val w = writer ?: return
        writer = null
        try {
            w.close()
        } catch (e: Exception) {
            return
        }
        val t = tmpFile ?: return
        if (!t.renameTo(file)) {
            t.copyTo(file, overwrite = true)
            t.delete()
        }
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
            main.post { onRenamed(target) }
        }
        return file.nameWithoutExtension
    }

    @JavascriptInterface
    fun openMenu() {
        main.post { onMenu() }
    }

    @JavascriptInterface
    fun hideSelectionToolbar() {
        main.post { onHideToolbar() }
    }

    @JavascriptInterface
    fun insert(kind: String) {
        main.post { onInsert(kind) }
    }

    @JavascriptInterface
    fun readChunk(id: Int): String {
        val uri = uris[id] ?: return ""
        var s = streams[id]
        if (s == null) {
            s = context.contentResolver.openInputStream(uri)
            if (s == null) return ""
            streams[id] = s
        }
        val buf = ByteArray(CHUNK_BYTES)
        var n = 0
        while (n < CHUNK_BYTES) {
            val r = s.read(buf, n, CHUNK_BYTES - n)
            if (r < 0) break
            n += r
        }
        if (n == 0) {
            release(id)
            return ""
        }
        return Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
    }

    @JavascriptInterface
    fun release(id: Int) {
        try {
            streams.remove(id)?.close()
        } catch (e: Exception) {
        }
        uris.remove(id)
    }
}

private fun displayName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: ""
        }
    } catch (e: Exception) {
    }
    return ""
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
    val context = LocalContext.current
    var webRef: WebView? by remember { mutableStateOf(null) }
    val holder = remember { arrayOfNulls<EditorBridge>(1) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { picked ->
        val br = holder[0]
        val w = webRef
        if (br != null && w != null) {
            for (uri in picked) {
                val id = br.register(uri)
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val name = displayName(context, uri)
                w.evaluateJavascript(
                    "attachImage(" + id + "," + JSONObject.quote(mime) + "," + JSONObject.quote(name) + ")",
                    null
                )
            }
        }
    }

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
                val bridge = EditorBridge(
                    ctx,
                    file,
                    onMenu,
                    onRenamed,
                    { view.hideSelectionToolbar() },
                    { kind ->
                        if (kind == "image") {
                            pickImages.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        } else {
                            Toast.makeText(
                                ctx,
                                "Эта вставка появится в следующем обновлении",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
                holder[0] = bridge
                view.addJavascriptInterface(bridge, "Android")
                view.loadUrl("file:///android_asset/editor.html")
                webRef = view
                onWeb(view)
                view
            }
        )
    }
}
