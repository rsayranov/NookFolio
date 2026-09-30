package com.nookfolio.app

import android.content.SharedPreferences
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = NoteStore(this)
        val prefs = getSharedPreferences("nookfolio", MODE_PRIVATE)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(store, prefs) { web = it }
                }
            }
        }
    }

    override fun onPause() {
        web?.evaluateJavascript("flush()", null)
        super.onPause()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppScreen(store: NoteStore, prefs: SharedPreferences, onWebChange: (WebView?) -> Unit) {
    val context = LocalContext.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var opened by remember {
        mutableStateOf(prefs.getString("last", null)?.let { File(it) }?.takeIf { it.exists() })
    }
    var session by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf(opened?.parentFile ?: store.root) }
    val expanded = remember { mutableStateListOf<String>() }
    var version by remember { mutableIntStateOf(0) }
    var trash by remember { mutableStateOf(store.listTrash()) }

    var createKind by remember { mutableStateOf<String?>(null) }
    var actionItem by remember { mutableStateOf<File?>(null) }
    var renameItem by remember { mutableStateOf<File?>(null) }
    var moveItem by remember { mutableStateOf<File?>(null) }
    var showTrash by remember { mutableStateOf(false) }

    val expandedSet = expanded.toSet()
    val rows = remember(version, expandedSet) { store.flatten(expandedSet) }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun refresh() {
        version++
        trash = store.listTrash()
    }

    fun withFlush(action: () -> Unit) {
        val w = web
        if (w != null && opened != null) {
            w.evaluateJavascript("flush()") { action() }
        } else {
            action()
        }
    }

    fun openNote(f: File) {
        val show: () -> Unit = {
            opened = f
            session++
            target = f.parentFile ?: store.root
            prefs.edit().putString("last", f.path).apply()
            scope.launch { drawer.close() }
        }
        val w = web
        if (w != null && opened != null) {
            w.evaluateJavascript("flush()") { show() }
        } else {
            show()
        }
    }

    fun applyRemap(old: File, new: File) {
        val cur = opened
        if (cur != null) {
            val moved = store.remap(cur, old, new)
            if (moved != cur) {
                opened = moved
                session++
                prefs.edit().putString("last", moved.path).apply()
            }
        }
        target = store.remap(target, old, new)
        val updated = expanded.map { store.remap(File(it), old, new).path }
        expanded.clear()
        expanded.addAll(updated)
    }

    fun applyTrash(old: File) {
        val cur = opened
        if (cur != null &&
            (cur.path == old.path || cur.path.startsWith(old.path + File.separator))
        ) {
            opened = null
            session++
            web = null
            onWebChange(null)
            prefs.edit().remove("last").apply()
        }
        if (target.path == old.path || target.path.startsWith(old.path + File.separator)) {
            target = old.parentFile ?: store.root
        }
        expanded.removeAll { it == old.path || it.startsWith(old.path + File.separator) }
    }

    fun doDelete(file: File) {
        withFlush {
            if (store.moveToTrash(file)) {
                applyTrash(file)
                refresh()
            } else {
                toast("Не удалось удалить")
            }
        }
    }

    fun doRename(file: File, name: String) {
        withFlush {
            val renamed = store.rename(file, name)
            if (renamed == null) {
                toast("Такое имя уже есть или оно пустое")
            } else if (renamed != file) {
                applyRemap(file, renamed)
                refresh()
            }
        }
    }

    fun doMove(file: File, dest: File) {
        withFlush {
            val moved = store.move(file, dest)
            if (moved == null) {
                toast("В этой папке уже есть элемент с таким именем")
            } else {
                applyRemap(file, moved)
                if (dest != store.root && dest.path !in expanded) expanded.add(dest.path)
                refresh()
            }
        }
    }

    fun doRestore(entry: TrashEntry) {
        val restored = store.restore(entry)
        if (restored == null) {
            toast("Не удалось вернуть")
        } else {
            refresh()
        }
    }

    fun doCreate(kind: String, name: String) {
        if (target != store.root && target.path !in expanded) expanded.add(target.path)
        if (kind == "folder") {
            if (store.createFolder(target, name) == null) {
                toast("Такое имя уже есть или оно пустое")
            }
        } else {
            val created = store.createNote(target, name)
            if (created == null) {
                toast("Такое имя уже есть или оно пустое")
            } else {
                openNote(created)
            }
        }
        refresh()
    }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    val trashLabel = if (trash.isEmpty()) "🗑 Корзина" else "🗑 Корзина (" + trash.size + ")"

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight()) {
                    Text(
                        "NookFolio",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(16.dp)
                    )
                    Row(
                        Modifier.padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { createKind = "folder" }) { Text("Папка") }
                        Button(onClick = { createKind = "note" }) { Text("Заметка") }
                    }
                    Text(
                        "Создать в: " + (if (target == store.root) "корень" else target.name),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Text(
                        "Удерживайте элемент: переименовать, переместить, удалить",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    HorizontalDivider()
                    if (rows.isEmpty()) {
                        Text("Пока пусто", Modifier.padding(16.dp))
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        items(rows, key = { it.first.file.path }) { (item, depth) ->
                            val isOpened = item.file == opened
                            val isTarget = item.isFolder && item.file == target
                            val isExpanded = item.file.path in expanded
                            Text(
                                text = (if (item.isFolder) {
                                    if (isExpanded) "▾ 📁 " else "▸ 📁 "
                                } else "     📝 ") + item.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isOpened || isTarget)
                                            MaterialTheme.colorScheme.secondaryContainer
                                        else Color.Transparent
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            if (item.isFolder) {
                                                target = item.file
                                                if (isExpanded) expanded.remove(item.file.path)
                                                else expanded.add(item.file.path)
                                            } else {
                                                openNote(item.file)
                                            }
                                        },
                                        onLongClick = { actionItem = item.file }
                                    )
                                    .padding(
                                        start = (16 + depth * 16).dp,
                                        top = 12.dp,
                                        bottom = 12.dp,
                                        end = 16.dp
                                    )
                            )
                        }
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = {
                            trash = store.listTrash()
                            showTrash = true
                        },
                        modifier = Modifier.padding(8.dp)
                    ) { Text(trashLabel) }
                }
            }
        }
    ) {
        val note = opened
        if (note != null) {
            key(session) {
                EditorScreen(
                    file = note,
                    onMenu = { scope.launch { drawer.open() } },
                    onRenamed = { f ->
                        opened = f
                        prefs.edit().putString("last", f.path).apply()
                        version++
                    },
                    onWeb = { web = it; onWebChange(it) }
                )
            }
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TextButton(onClick = { scope.launch { drawer.open() } }) {
                    Text("☰", style = MaterialTheme.typography.titleLarge)
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Откройте заметку в меню ☰")
                }
            }
        }
    }

    createKind?.let { kind ->
        NameDialog(
            title = if (kind == "folder") "Новая папка" else "Новая заметка",
            initial = "",
            confirmLabel = "Создать",
            onConfirm = { name ->
                createKind = null
                doCreate(kind, name)
            },
            onDismiss = { createKind = null }
        )
    }

    actionItem?.let { file ->
        ActionsDialog(
            name = if (file.isDirectory) file.name else file.nameWithoutExtension,
            onRename = {
                actionItem = null
                renameItem = file
            },
            onMove = {
                actionItem = null
                moveItem = file
            },
            onDelete = {
                actionItem = null
                doDelete(file)
            },
            onDismiss = { actionItem = null }
        )
    }

    renameItem?.let { file ->
        NameDialog(
            title = "Переименовать",
            initial = if (file.isDirectory) file.name else file.nameWithoutExtension,
            confirmLabel = "Готово",
            onConfirm = { name ->
                renameItem = null
                doRename(file, name)
            },
            onDismiss = { renameItem = null }
        )
    }

    moveItem?.let { file ->
        val options = store.allFolders().filter { (folder, _) ->
            folder != file.parentFile &&
                !(file.isDirectory &&
                    (folder.path == file.path || folder.path.startsWith(file.path + File.separator)))
        }
        MoveDialog(
            title = "Переместить в…",
            folders = options,
            rootFile = store.root,
            onPick = { dest ->
                moveItem = null
                doMove(file, dest)
            },
            onDismiss = { moveItem = null }
        )
    }

    if (showTrash) {
        TrashDialog(
            entries = trash,
            onRestore = { doRestore(it) },
            onDelete = {
                store.deleteForever(it)
                refresh()
            },
            onEmpty = {
                store.emptyTrash()
                refresh()
            },
            onDismiss = { showTrash = false }
        )
    }
}
