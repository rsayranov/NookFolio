package com.nookfolio.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Название") }
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun ActionsDialog(
    name: String,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column {
                TextButton(onClick = onRename) { Text("Переименовать") }
                TextButton(onClick = onMove) { Text("Переместить в…") }
                TextButton(onClick = onDelete) { Text("В корзину") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun MoveDialog(
    title: String,
    folders: List<Pair<File, Int>>,
    rootFile: File,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(folders, key = { it.first.path }) { (folder, depth) ->
                    Text(
                        text = if (folder == rootFile) "🏠 Корень" else "📁 " + folder.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(folder) }
                            .padding(
                                start = (8 + depth * 16).dp,
                                top = 12.dp,
                                bottom = 12.dp,
                                end = 8.dp
                            )
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun TrashDialog(
    entries: List<TrashEntry>,
    onRestore: (TrashEntry) -> Unit,
    onDelete: (TrashEntry) -> Unit,
    onEmpty: () -> Unit,
    onDismiss: () -> Unit
) {
    var pending by remember { mutableStateOf<TrashEntry?>(null) }
    var confirmAll by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Корзина") },
        text = {
            if (entries.isEmpty()) {
                Text("Корзина пуста")
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(entries, key = { it.file.path }) { entry ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text((if (entry.isFolder) "📁 " else "📝 ") + entry.name)
                            Row {
                                TextButton(onClick = { onRestore(entry) }) { Text("Вернуть") }
                                TextButton(onClick = { pending = entry }) { Text("Удалить навсегда") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            if (entries.isNotEmpty()) {
                TextButton(onClick = { confirmAll = true }) { Text("Очистить всё") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )

    pending?.let { entry ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Удалить навсегда?") },
            text = { Text("«" + entry.name + "» будет удалено без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = { onDelete(entry); pending = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Отмена") } }
        )
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Очистить корзину?") },
            text = { Text("Всё содержимое корзины будет удалено без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = { onEmpty(); confirmAll = false }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Отмена") } }
        )
    }
}
