package com.nookfolio.app

import android.content.Context
import java.io.File

data class Item(val file: File) {
    val isFolder: Boolean get() = file.isDirectory
    val name: String get() = if (isFolder) file.name else file.nameWithoutExtension
}

class NoteStore(context: Context) {
    val root: File = File(context.filesDir, "notes").apply { mkdirs() }

    fun list(dir: File): List<Item> =
        (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") && (it.isDirectory || it.extension == "html") }
            .map { Item(it) }
            .sortedWith(compareBy({ !it.isFolder }, { it.name.lowercase() }))

    fun flatten(expanded: Set<String>, dir: File = root, depth: Int = 0): List<Pair<Item, Int>> {
        val out = mutableListOf<Pair<Item, Int>>()
        for (item in list(dir)) {
            out += item to depth
            if (item.isFolder && item.file.path in expanded) {
                out += flatten(expanded, item.file, depth + 1)
            }
        }
        return out
    }

    fun createFolder(parent: File, name: String): File? {
        val n = clean(name)
        if (n.isEmpty()) return null
        val f = File(parent, n)
        return if (!f.exists() && f.mkdirs()) f else null
    }

    fun createNote(parent: File, name: String): File? {
        val n = clean(name)
        if (n.isEmpty()) return null
        val f = File(parent, "$n.html")
        return if (!f.exists() && f.createNewFile()) f else null
    }

    private fun clean(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
