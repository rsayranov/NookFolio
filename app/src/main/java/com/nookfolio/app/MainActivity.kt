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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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

data class Edit(val kind: String, val file: File)

data class TreeRow(val key: String, val item: Item?, val depth: Int)

@Composable
fun InlineNameField(
    prefix: String,
    initial: String,
    depth: Int,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit
) {
    var value by remember {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var hadFocus by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    fun finish(commit: Boolean) {
        if (finished) return
        finished = true
        if (commit) onCommit(value.text) else onCancel()
    }

    LaunchedEffect(Unit) {
        delay(150)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    BackHandler { finish(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = (16 + depth * 16).dp, top = 8.dp, bottom = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(prefix)
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { finish(value.text.isNotBlank()) }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    if (state.isFocused) {
                        hadFocus = true
                    } else if (hadFocus) {
                        finish(value.text.isNotBlank())
                    }
                }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppScreen(store: NoteStore, prefs: SharedPreferences, onWebChange: (WebView?) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val noRipple = remember { MutableInteractionSource() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var web: WebView? by remember { mutableStateOf(null) }
    var opened by remember {
        mutableStateOf(prefs.getString("last", null)?.let { File(it) }?.takeIf { it.exists() })
    }
    var session by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf(opened?.parentFile ?: store.root) }
    val expanded = remember { mutableStateListOf<String>() }
    var version by remember { mutableIntStateOf(0) }
    var trash by remember { mutableStateOf(store.listTrash()) }

    var edit by remember { mutableStateOf<Edit?>(null) }
    var actionItem by remember { mutableStateOf<File?>(null) }
    var moveItem by remember { mutableStateOf<File?>(null) }
    var showTrash by remember { mutableStateOf(false) }

    val expandedSet = expanded.toSet()
    val rows = remember(version, expandedSet) { store.flatten(expandedSet) }

    val display = remember(rows, edit) {
        val list = mutableListOf<TreeRow>()
        val e = edit
        if (e != null && e.kind != "rename" && e.file == store.root) {
            list += TreeRow("new", null, 0)
        }
        for ((item, depth) in rows) {
            list += TreeRow(item.file.path, item, depth)
            if (e != null && e.kind != "rename" && e.file == item.file) {
                list += TreeRow("new", null, depth + 1)
            }
        }
        list
    }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun refresh() {
        version++
        trash = store.listTrash()
    }

    fun finishEdit() {
        focusManager.clearFocus()
        keyboard?.hide()
        if (edit != null) edit = null
    }

    fun guard(action: () -> Unit) {
        if (edit != null) {
            finishEdit()
        } else {
            action()
        }
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

    fun startCreate(kind: String) {
        if (target != store.root && target.path !in expanded) expanded.add(target.path)
        edit = Edit(kind, target)
    }

    fun doCreate(kind: String, parent: File, name: String) {
        if (kind == "folder") {
            if (store.createFolder(parent, name) == null) {
                toast("Такое имя уже есть или оно пустое")
            }
        } else {
            val created = store.createNote(parent, name)
            if (created == null) {
                toast("Такое имя уже есть или оно пустое")
            } else {
                openNote(created)
            }
        }
        refresh()
    }

    LaunchedEffect(drawer.currentValue) {
        if (drawer.currentValue == DrawerValue.Closed && edit != null) finishEdit()
    }

    LaunchedEffect(edit) {
        val e = edit
        if (e != null && e.kind != "rename") {
            delay(200)
            val index = display.indexOfFirst { it.key == "new" }
            if (index >= 0) listState.animateScrollToItem(index)
        }
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (target == store.root)
                                    MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent
                            )
                            .clickable { guard { target = store.root } }
                            .padding(16.dp)
                    )
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = { guard { startCreate("folder") } }) { Text("Папка") }
                        Button(onClick = { guard { startCreate("note") } }) { Text("Заметка") }
                    }
                    HorizontalDivider()
                    if (display.isEmpty()) {
                        Text("Пока пусто", Modifier.padding(16.dp))
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = noRipple,
                                indication = null,
                                onClick = { guard { target = store.root } }
                            ),
                        state = listState
                    ) {
                        items(display, key = { it.key }) { row ->
                            val item = row.item
                            val e = edit
                            if (item == null) {
                                if (e != null) {
                                    InlineNameField(
                                        prefix = if (e.kind == "folder") "▸ 📁 " else "     📝 ",
                                        initial = "",
                                        depth = row.depth,
                                        onCommit = { name ->
                                            edit = null
                                            doCreate(e.kind, e.file, name)
                                        },
                                        onCancel = { edit = null }
                                    )
                                }
                            } else {
                                val isExpanded = item.file.path in expanded
                                val prefix = if (item.isFolder) {
                                    if (isExpanded) "▾ 📁 " else "▸ 📁 "
                                } else "     📝 "
                                if (e != null && e.kind == "rename" && e.file == item.file) {
                                    InlineNameField(
                                        prefix = prefix,
                                        initial = item.name,
                                        depth = row.depth,
                                        onCommit = { name ->
                                            edit = null
                                            doRename(item.file, name)
                                        },
                                        onCancel = { edit = null }
                                    )
                                } else {
                                    val isOpened = item.file == opened
                                    val isTarget = item.isFolder && item.file == target
                                    Text(
                                        text = prefix + item.name,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (isOpened || isTarget)
                                                    MaterialTheme.colorScheme.secondaryContainer
                                                else Color.Transparent
                                            )
                                            .combinedClickable(
                                                onClick = {
                                                    guard {
                                                        if (item.isFolder) {
                                                            target = item.file
                                                            if (isExpanded) expanded.remove(item.file.path)
                                                            else expanded.add(item.file.path)
                                                        } else {
                                                            openNote(item.file)
                                                        }
                                                    }
                                                },
                                                onLongClick = {
                                                    guard {
                                                        haptic.performHapticFeedback(
                                                            HapticFeedbackType.LongPress
                                                        )
                                                        actionItem = item.file
                                                    }
                                                }
                                            )
                                            .padding(
                                                start = (16 + row.depth * 16).dp,
                                                top = 12.dp,
                                                bottom = 12.dp,
                                                end = 16.dp
                                            )
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = {
                            guard {
                                trash = store.listTrash()
                                showTrash = true
                            }
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

    actionItem?.let { file ->
        ActionsDialog(
            name = if (file.isDirectory) file.name else file.nameWithoutExtension,
            onRename = {
                actionItem = null
                edit = Edit("rename", file)
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
