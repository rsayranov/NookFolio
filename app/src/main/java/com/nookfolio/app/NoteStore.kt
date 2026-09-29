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

    fun createFolder(parent: File, name: String): Boolean {
        val n = clean(name)
        if (n.isEmpty()) return false
        return File(parent, n).mkdirs()
    }

    fun createNote(parent: File, name: String): Boolean {
        val n = clean(name)
        if (n.isEmpty()) return false
        val f = File(parent, "$n.html")
        return if (f.exists()) false else f.createNewFile()
    }

    private fun clean(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
