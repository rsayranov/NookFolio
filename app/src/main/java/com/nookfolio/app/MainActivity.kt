package com.nookfolio.app

import android.content.SharedPreferences
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@Composable
fun AppScreen(store: NoteStore, prefs: SharedPreferences, onWebChange: (WebView) -> Unit) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var web by remember { mutableStateOf<WebView?>(null) }
    var opened by remember {
        mutableStateOf(prefs.getString("last", null)?.let { File(it) }?.takeIf { it.exists() })
    }
    var session by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf(opened?.parentFile ?: store.root) }
    val expanded = remember { mutableStateListOf<String>() }
    var dialog by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val expandedSet = expanded.toSet()
    val rows = remember(version, expandedSet) { store.flatten(expandedSet) }

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

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

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
                        Button(onClick = { dialog = "folder" }) { Text("Папка") }
                        Button(onClick = { dialog = "note" }) { Text("Заметка") }
                    }
                    Text(
                        "Создать в: " + (if (target == store.root) "корень" else target.name),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
                                    .clickable {
                                        if (item.isFolder) {
                                            target = item.file
                                            if (isExpanded) expanded.remove(item.file.path)
                                            else expanded.add(item.file.path)
                                        } else {
                                            openNote(item.file)
                                        }
                                    }
                                    .padding(
                                        start = (16 + depth * 16).dp,
                                        top = 12.dp,
                                        bottom = 12.dp,
                                        end = 16.dp
                                    )
                            )
                        }
                    }
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

    dialog?.let { kind ->
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (kind == "folder") "Новая папка" else "Новая заметка") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Название") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (target != store.root && target.path !in expanded) {
                        expanded.add(target.path)
                    }
                    if (kind == "folder") {
                        store.createFolder(target, text)
                    } else {
                        store.createNote(target, text)?.let { openNote(it) }
                    }
                    version++
                    dialog = null
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { dialog = "" .let { null } }) { Text("Отмена") } }
        )
    }
}
