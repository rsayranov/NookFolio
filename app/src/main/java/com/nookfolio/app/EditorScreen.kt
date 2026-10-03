package com.nookfolio.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Base64
import android.view.ActionMode
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

const val ATT_HOST = "https://nookfolio.local/att/"
private const val CHUNK_CHARS = 1_000_000
private const val MAX_SIDE = 2048
private const val JPEG_QUALITY = 85
private const val LARGE_BYTES = 100L * 1024L * 1024L

fun extFor(mime: String): String {
    val m = mime.lowercase()
    return when (m) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(m) ?: "bin"
    }
}

fun mimeFor(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "m4a" -> "audio/mp4"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

private fun safeExt(name: String, mime: String): String {
    val e = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8)
    return if (e.isNotEmpty()) e else extFor(mime)
}

private fun queryName(context: Context, uri: Uri): String {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: ""
        }
    } catch (e: Throwable) {
    }
    return ""
}

private fun querySize(context: Context, uri: Uri): Long {
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
    } catch (e: Throwable) {
    }
    return 0L
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

private fun sbEndsWith(sb: StringBuilder, s: String): Boolean {
    val off = sb.length - s.length
    if (off < 0) return false
    for (i in s.indices) {
        if (sb[off + i] != s[i]) return false
    }
    return true
}

private fun dataMarkerAttr(sb: StringBuilder): String? {
    if (sbEndsWith(sb, "src=\"data:")) return "src"
    if (sbEndsWith(sb, "href=\"data:")) return "href"
    return null
}

/** Читает файл заметки потоком: вложения (data:...) уходят в файлы, в тексте остаются ссылки. */
private fun expandNote(src: File, attDir: File): String {
    val out = StringBuilder()
    val header = StringBuilder()
    val pend = StringBuilder()
    var os: OutputStream? = null
    var name = ""
    var attr = "src"
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
                    if (c == ':') {
                        val a = dataMarkerAttr(out)
                        if (a != null) {
                            out.setLength(out.length - (a.length + 7))
                            attr = a
                            header.setLength(0)
                            state = 1
                        }
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
                            out.append(attr).append("=\"data:").append(h).append(',')
                            state = 0
                        }
                    } else if (c == '"') {
                        out.append(attr).append("=\"data:").append(header).append('"')
                        state = 0
                    } else if (header.length > 200) {
                        out.append(attr).append("=\"data:").append(header).append(c)
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
                        out.append(attr).append("=\"").append(ATT_HOST).append(name).append('"')
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
    val srcP = "src=\"" + ATT_HOST
    val hrefP = "href=\"" + ATT_HOST
    var pos = 0
    while (true) {
        val i1 = html.indexOf(srcP, pos)
        val i2 = html.indexOf(hrefP, pos)
        val i: Int
        val prefix: String
        val attr: String
        if (i1 >= 0 && (i2 < 0 || i1 < i2)) {
            i = i1
            prefix = srcP
            attr = "src"
        } else if (i2 >= 0) {
            i = i2
            prefix = hrefP
            attr = "href"
        } else {
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
        w.write(attr + "=\"data:" + mimeFor(name) + ";base64,")
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

private fun readOrientation(context: Context, uri: Uri): Int {
    return try {
        context.contentResolver.openInputStream(uri)?.use { s ->
            ExifInterface(s).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Throwable) {
        ExifInterface.ORIENTATION_NORMAL
    }
}

private fun orientationMatrix(o: Int): Matrix {
    val m = Matrix()
    when (o) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            m.setRotate(180f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            m.setRotate(90f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            m.setRotate(-90f)
            m.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
    }
    return m
}

/** Уменьшает фото до MAX_SIDE по длинной стороне и сохраняет в JPEG. false, если не получилось. */
private fun compressImage(context: Context, uri: Uri, dst: File): Boolean {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options()
    bounds.inJustDecodeBounds = true
    try {
        resolver.openInputStream(uri)?.use { s -> BitmapFactory.decodeStream(s, null, bounds) }
    } catch (e: Throwable) {
        return false
    }
    val w0 = bounds.outWidth
    val h0 = bounds.outHeight
    if (w0 <= 0 || h0 <= 0) return false

    var sample = 1
    while (maxOf(w0, h0) / (sample * 2) >= MAX_SIDE) sample *= 2
    val orientation = readOrientation(context, uri)

    var attempt = 0
    while (attempt < 2) {
        var decoded: Bitmap? = null
        var turned: Bitmap? = null
        var flat: Bitmap? = null
        try {
            val opts = BitmapFactory.Options()
            opts.inSampleSize = if (attempt == 0) sample else sample * 2
            val d = resolver.openInputStream(uri)?.use { s -> BitmapFactory.decodeStream(s, null, opts) }
                ?: return false
            decoded = d

            val m = orientationMatrix(orientation)
            val longSide = maxOf(d.width, d.height)
            if (longSide > MAX_SIDE) {
                val sc = MAX_SIDE.toFloat() / longSide
                m.postScale(sc, sc)
            }
            val t = if (m.isIdentity) d else Bitmap.createBitmap(d, 0, 0, d.width, d.height, m, true)
            turned = t

            var target = t
            if (t.hasAlpha()) {
                val f = Bitmap.createBitmap(t.width, t.height, Bitmap.Config.ARGB_8888)
                flat = f
                val cv = Canvas(f)
                cv.drawColor(Color.WHITE)
                cv.drawBitmap(t, 0f, 0f, null)
                target = f
            }
            FileOutputStream(dst).use { o ->
                target.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, o)
            }
            return true
        } catch (e: OutOfMemoryError) {
            attempt++
        } catch (e: Throwable) {
            return false
        } finally {
            flat?.recycle()
            if (turned != null && turned !== decoded) turned.recycle()
            decoded?.recycle()
        }
    }
    return false
}

@Suppress("DEPRECATION")
private fun newRecorder(context: Context): MediaRecorder {
    return if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
}

/** Превращает замеры громкости записи в 40 уровней (8..100) для волны. */
private fun buildWave(list: List<Int>): String {
    val n = list.size
    if (n == 0) return ""
    val bars = 40
    val vals = IntArray(bars)
    var maxV = 1
    for (i in 0 until bars) {
        val from = i * n / bars
        val to = maxOf(from + 1, (i + 1) * n / bars).coerceAtMost(n)
        var m = 0
        for (k in from until to) {
            if (list[k] > m) m = list[k]
        }
        vals[i] = m
        if (m > maxV) maxV = m
    }
    val sb = StringBuilder()
    for (i in 0 until bars) {
        val lv = (Math.pow(vals[i].toDouble() / maxV, 0.6) * 100.0).toInt().coerceIn(8, 100)
        if (i > 0) sb.append(',')
        sb.append(lv)
    }
    return sb.toString()
}

class LimitedInputStream(private val src: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val r = src.read()
        if (r >= 0) left--
        return r
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = src.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n.toLong()
        return n
    }

    override fun close() {
        src.close()
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
        val headers = HashMap<String, String>()
        headers["Access-Control-Allow-Origin"] = "*"
        headers["Accept-Ranges"] = "bytes"
        if (name.isEmpty() || name.contains('/') || name.contains("..")) {
            return WebResourceResponse("text/plain", "utf-8", 400, "Bad Request", headers, ByteArrayInputStream(ByteArray(0)))
        }
        val f = File(attDir, name)
        if (!f.exists()) {
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers, ByteArrayInputStream(ByteArray(0)))
        }
        val len = f.length()
        val mime = mimeFor(name)
        val rangeHeader = request.requestHeaders["Range"] ?: request.requestHeaders["range"]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").substringBefore(',').trim()
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash).toLongOrNull()
                val b = spec.substring(dash + 1).toLongOrNull()
                val start: Long
                val end: Long
                if (a == null) {
                    val n = b ?: 0L
                    start = maxOf(0L, len - n)
                    end = len - 1
                } else {
                    start = a
                    end = if (b == null || b >= len) len - 1 else b
                }
                if (len == 0L || start >= len || start > end) {
                    headers["Content-Range"] = "bytes */$len"
                    return WebResourceResponse("text/plain", "utf-8", 416, "Range Not Satisfiable", headers, ByteArrayInputStream(ByteArray(0)))
                }
                val fis = FileInputStream(f)
                fis.channel.position(start)
                val size = end - start + 1
                headers["Content-Range"] = "bytes $start-$end/$len"
                headers["Content-Length"] = size.toString()
                return WebResourceResponse(mime, null, 206, "Partial Content", headers, LimitedInputStream(fis, size))
            }
        }
        headers["Content-Length"] = len.toString()
        return WebResourceResponse(mime, null, 200, "OK", headers, FileInputStream(f))
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
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private val amps = ArrayList<Int>()
    private val sampler = object : Runnable {
        override fun run() {
            synchronized(this@EditorBridge) {
                val r = recorder ?: return
                try {
                    amps.add(r.maxAmplitude)
                } catch (e: Throwable) {
                }
                main.postDelayed(this, 80L)
            }
        }
    }

    var web: WebView? = null

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

    @JavascriptInterface
    fun voiceStop() {
        io.execute { finishRecording(true, false) }
    }

    @JavascriptInterface
    fun voiceCancel() {
        io.execute { finishRecording(false, false) }
    }

    private fun exportCopy(url: String, name: String): Pair<Uri, String>? {
        val fileName = url.removePrefix(ATT_HOST).substringBefore('?')
        if (fileName.isEmpty() || fileName.contains('/') || fileName.contains("..")) return null
        val src = File(attDir, fileName)
        if (!src.exists()) return null
        val dir = File(context.cacheDir, "open")
        dir.deleteRecursively()
        dir.mkdirs()
