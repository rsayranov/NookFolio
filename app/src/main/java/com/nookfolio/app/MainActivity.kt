package com.nookfolio.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = NoteStore(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BrowserScreen(store)
                }
            }
        }
    }
}

@Composable
fun BrowserScreen(store: NoteStore) {
    var current by remember { mutableStateOf(store.root) }
    var version by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val atRoot = current == store.root
    val entries = remember(current, version) { store.list(current) }

    BackHandler(enabled = !atRoot) { current = current.parentFile!! }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Text(
            if (atRoot) "NookFolio" else current.name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp)
        )
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { dialog = "folder" }) { Text("Новая папка") }
            Button(onClick = { dialog = "note" }) { Text("Новая заметка") }
        }
        if (entries.isEmpty()) {
            Text("Здесь пока пусто", Modifier.padding(16.dp))
        }
        LazyColumn {
            items(entries, key = { it.file.path }) { item ->
                Text(
                    (if (item.isFolder) "📁 " else "📝 ") + item.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (item.isFolder) current = item.file }
                        .padding(16.dp)
                )
                HorizontalDivider()
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
                    if (kind == "folder") store.createFolder(current, text)
                    else store.createNote(current, text)
                    version++
                    dialog = null
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Отмена") } }
        )
    }
}
