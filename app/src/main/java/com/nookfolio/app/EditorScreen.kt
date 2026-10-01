package com.nookfolio.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ActionMode
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

const val ATT_HOST = "https://nookfolio.local/att/"
private const val CHUNK_CHARS = 1_000_000
private const val MARKER = "src=\"data:"

fun extFor(mime: String): String = when (mime.lowercase()) {
    "image/jpeg", "image/jpg" -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    "image/gif" -> "gif"
    "image/heic" -> "heic"
    "image/heif" -> "heif"
    else -> "bin"
}

fun mimeFor(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    else -> "application/octet-stream"
}

private fun chunkBounds(s: String): List<IntArray> {
    val list = ArrayList<IntArray>()
    var start = 0
    while (start < s.length) {
        var end = minOf(start + CHUNK_CHARS, s.length)
        if (end < s.length && Character.isHighSurrogate(s[end - 1])) end--
        list.add(intArrayOf(start, end))
        start = end
    }
    return list
}

/** Читает файл заметки потоком: вложения (data:...) уходят в файлы, в тексте остаются ссылки. */
private fun expandNote(src: File, attDir: File): String {
    val out = StringBuilder()
    val header = StringBuilder()
    val pend = StringBuilder()
    var os: OutputStream? = null
    var name = ""
    var count = 0
    var state = 0
    src.bufferedReader(Charsets.UTF_8, 1 shl 16).use { reader ->
        val buf = CharArray(1 shl 16)
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val c = buf[i]
                if (state == 0) {
                    out.append(c)
                    if (c == ':' && out.length >= MARKER.length &&
                        out.substring(out.length - MARKER.length) == MARKER
                    ) {
                        out.setLength(out.length - MARKER.length)
                        header.setLength(0)
                        state = 1
                    }
                } else if (state == 1) {
                    if (c == ',') {
                        val h = header.toString()
                        if (h.endsWith(";base64")) {
                            val mime = h.substringBefore(';').ifEmpty { "application/octet-stream" }
                            count++
                            name = count.toString() + "." + extFor(mime)
                            os = BufferedOutputStream(FileOutputStream(File(attDir, name)), 1 shl 16)
                            pend.setLength(0)
                            state = 2
                        } else {
                            out.append(MARKER).append(h).append(',')
                            state = 0
                        }
                    } else if (c == '"') {
                        out.append(MARKER).append(header).append('"')
                        state = 0
                    } else {
                        header.append(c)
                    }
                } else {
                    if (c == '"') {
                        val rest = pend.toString()
                        if (rest.isNotEmpty()) os?.write(Base64.decode(rest, Base64.DEFAULT))
                        os?.close()
                        os = null
                        out.append("src=\"").append(ATT_HOST).append(name).append('"')
                        state = 0
                    } else if (c != '\n' && c != '\r' && c != ' ') {
                        pend.append(c)
                        if (pend.length >= 16384) {
                            val cut = pend.length / 4 * 4
                            os?.write(Base64.decode(pend.substring(0, cut), Base64.DEFAULT))
                            pend.delete(0, cut)
                        }
                    }
                }
            }
        }
    }
    os?.close()
    return out.toString()
}

/** Пишет заметку: ссылки на вложения снова превращаются в data:-вставки, поток по 3 МБ. */
private fun writeExpanded(html: String, w: Writer, attDir: File) {
    val prefix = "src=\"" + ATT_HOST
    var pos = 0
    while (true) {
        val i = html.indexOf(prefix, pos)
        if (i < 0) {
            w.write(html, pos, html.length - pos)
            return
        }
        val nameStart = i + prefix.length
        val q = html.indexOf('"', nameStart)
        if (q < 0) {
            w.write(html, pos, html.length - pos)
            return
        }
        val name = html.substring(nameStart, q)
        w.write(html, pos, i - pos)
        val f = File(attDir, name)
        val safe = name.isNotEmpty() && !name.contains('/') && !name.contains("..") && f.exists()
        if (!safe) {
            w.write(html, i, q + 1 - i)
            pos = q + 1
            continue
        }
        w.write("src=\"data:" + mimeFor(name) + ";base64,")
        FileInputStream(f).buffered(1 shl 16).use { ins ->
            val b = ByteArray(3 * 1024 * 1024)
            while (true) {
                var n = 0
                while (n < b.size) {
                    val r = ins.read(b, n, b.size - n)
                    if (r < 0) break
                    n += r
                }
                if (n == 0) break
                w.write(Base64.encodeToString(b, 0, n, Base64.NO_WRAP))
                if (n < b.size) break
            }
        }
        w.write("\"")
        pos = q + 1
    }
}

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

class AttClient(private val attDir: File, private val onGone: () -> Unit) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!url.startsWith(ATT_HOST)) return null
        val name = url.removePrefix(ATT_HOST).substringBefore('?').substringBefore('#')
        val headers = mapOf("Access-Control-Allow-Origin" to "*")
        if (name.isEmpty() || name.contains('/') || name.contains("..")) {
            return WebResourceResponse("text/plain", "utf-8", 400, "Bad Request", headers, ByteArrayInputStream(ByteArray(0)))
        }
        val f = File(attDir, name)
        if (!f.exists()) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers, ByteArrayInputStream(ByteArray(0)))
        }
        return WebResourceResponse(mimeFor(name), null, 200, "OK", headers, FileInputStream(f))
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onGone()
        return true
    }
}

class EditorBridge(
    private val context: Context,
    private var file: File,
    private val attDir: File,
    private val onMenu: () -> Unit,
    private val onRenamed: (File) -> Unit,
    private val onHideToolbar: () -> Unit,
    private val onInsert: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
    private val counter = AtomicInteger(0)
    private var skeleton = ""
    private var bounds: List<IntArray> = emptyList()
    private val saveBuf = StringBuilder()
    private var backedUp = false

    @JavascriptInterface
    fun loadStart(): Int {
        return try {
            attDir.deleteRecursively()
            attDir.mkdirs()
            skeleton = if (file.exists() && file.length() > 0) expandNote(file, attDir) else ""
            bounds = chunkBounds(skeleton)
            bounds.size
        } catch (t: Throwable) {
            skeleton = ""
            bounds = emptyList()
            -1
        }
    }

    @JavascriptInterface
    fun loadChunk(i: Int): String {
        if (i < 0 || i >= bounds.size) return ""
        return skeleton.substring(bounds[i][0], bounds[i][1])
    }

    @JavascriptInterface
    fun loadEnd() {
        skeleton = ""
        bounds = emptyList()
    }

    @JavascriptInterface
    fun saveBegin() {
        saveBuf.setLength(0)
    }

    @JavascriptInterface
    fun saveChunk(part: String) {
        saveBuf.append(part)
    }

    @JavascriptInterface
    fun saveEnd() {
        val html = saveBuf.toString()
        saveBuf.setLength(0)
        try {
            val t = File(file.path + ".tmp")
            OutputStreamWriter(BufferedOutputStream(FileOutputStream(t), 1 shl 16), Charsets.UTF_8).use { w ->
                writeExpanded(html, w, attDir)
            }
            if (!backedUp && file.exists() && file.length() > 0) {
                file.copyTo(File(file.path + ".bak"), overwrite = true)
                backedUp = true
            }
            if (!t.renameTo(file)) {
                t.copyTo(file, overwrite = true)
                t.delete()
            }
        } catch (e: Throwable) {
            main.post { Toast.makeText(context, "Не удалось сохранить заметку", Toast.LENGTH_LONG).show() }
        }
    }

    @JavascriptInterface
    fun attSize(): Long {
        return attDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    @JavascriptInterface
    fun title(): String = file.nameWithoutExtension

    @JavascriptInterface
    fun rename(newTitle: String): String {
        val n = newTitle.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (n.isEmpty() || n == file.nameWithoutExtension) return file.nameWithoutExtension
        val target = File(file.parentFile, "$n.html")
        if (target.exists()) return file.nameWithoutExtension
        val oldPath = file.path
        if (file.renameTo(target)) {
            val bak = File(oldPath + ".bak")
            if (bak.exists()) bak.renameTo(File(target.path + ".bak"))
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

    fun attachImages(uris: List<Uri>, view: WebView) {
        if (uris.isEmpty()) return
        view.evaluateJavascript("attachStart()", null)
        io.execute {
            for (uri in uris) {
                try {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val name = "u" + counter.incrementAndGet() + "." + extFor(mime)
                    attDir.mkdirs()
                    val dst = File(attDir, name)
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        FileOutputStream(dst).use { o -> ins.copyTo(o, 1 shl 16) }
                    }
                    if (dst.exists() && dst.length() > 0) {
                        val url = ATT_HOST + name
                        view.post { view.evaluateJavascript("attachDone(" + JSONObject.quote(url) + ")", null) }
                    }
                } catch (e: Throwable) {
                    main.post { Toast.makeText(context, "Не удалось добавить изображение", Toast.LENGTH_LONG).show() }
                }
            }
            view.post { view.evaluateJavascript("attachFinish()", null) }
        }
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
    val context = LocalContext.current
    var webRef: WebView? by remember { mutableStateOf(null) }
    var reload by remember { mutableIntStateOf(0) }
    val holder = remember { arrayOfNulls<EditorBridge>(1) }
    val attDir = remember { File(context.cacheDir, "att") }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { picked ->
        val br = holder[0]
        val w = webRef
        if (br != null && w != null && picked.isNotEmpty()) {
            br.attachImages(picked, w)
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

    key(reload) {
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val view = EditorWebView(ctx)
                    view.settings.javaScriptEnabled = true
                    view.webViewClient = AttClient(attDir) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(ctx, "Редактор перезапущен", Toast.LENGTH_LONG).show()
                            reload++
                        }
                    }
                    val bridge = EditorBridge(
                        ctx,
                        file,
                        attDir,
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
}
