package com.nookfolio.app

import android.content.Context
import java.io.File

data class Item(val file: File) {
    val isFolder: Boolean get() = file.isDirectory
    val name: String get() = if (isFolder) file.name else file.nameWithoutExtension
}

data class TrashEntry(val file: File, val originalParent: String) {
    val isFolder: Boolean get() = file.isDirectory
    val name: String get() = if (isFolder) file.name else file.nameWithoutExtension
}

class NoteStore(context: Context) {
    val root: File = File(context.filesDir, "notes").apply { mkdirs() }
    private val trashDir: File = File(root, ".trash").apply { mkdirs() }
    private val metaFile: File = File(trashDir, ".meta")

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

    fun allFolders(dir: File = root, depth: Int = 0): List<Pair<File, Int>> {
        val out = mutableListOf<Pair<File, Int>>()
        out += dir to depth
        for (item in list(dir)) {
            if (item.isFolder) out += allFolders(item.file, depth + 1)
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

    fun rename(file: File, newName: String): File? {
        val n = clean(newName)
        if (n.isEmpty()) return null
        val target = File(file.parentFile, if (file.isDirectory) n else "$n.html")
        if (target.path == file.path) return file
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    fun move(file: File, dest: File): File? {
        if (file.isDirectory &&
            (dest.path == file.path || dest.path.startsWith(file.path + File.separator))
        ) return null
        val target = File(dest, file.name)
        if (target.exists()) return null
        return if (file.renameTo(target)) target else null
    }

    fun remap(f: File, old: File, new: File): File {
        if (f.path == old.path) return new
        val prefix = old.path + File.separator
        if (f.path.startsWith(prefix)) return File(new, f.path.removePrefix(prefix))
        return f
    }

    fun moveToTrash(file: File): Boolean {
        val rel = file.parentFile?.relativeTo(root)?.path ?: ""
        val target = uniqueIn(trashDir, file.name, file.isDirectory)
        if (!file.renameTo(target)) return false
        val meta = readMeta()
        meta[target.name] = rel
        writeMeta(meta)
        return true
    }

    fun listTrash(): List<TrashEntry> {
        val meta = readMeta()
        return (trashDir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .map { TrashEntry(it, meta[it.name] ?: "") }
            .sortedBy { it.name.lowercase() }
    }

    fun restore(entry: TrashEntry): File? {
        var parent = File(root, entry.originalParent)
        if (!parent.isDirectory) parent = root
        val target = uniqueIn(parent, entry.file.name, entry.file.isDirectory)
        if (!entry.file.renameTo(target)) return null
        forget(entry.file.name)
        return target
    }

    fun deleteForever(entry: TrashEntry) {
        entry.file.deleteRecursively()
        forget(entry.file.name)
    }

    fun emptyTrash() {
        for (entry in listTrash()) entry.file.deleteRecursively()
        metaFile.delete()
    }

    private fun forget(trashName: String) {
        val meta = readMeta()
        meta.remove(trashName)
        writeMeta(meta)
    }

    private fun readMeta(): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (metaFile.exists()) {
            for (line in metaFile.readLines()) {
                val i = line.indexOf('|')
                if (i > 0) map[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        return map
    }

    private fun writeMeta(map: Map<String, String>) {
        metaFile.writeText(map.entries.joinToString("\n") { it.key + "|" + it.value })
    }

    private fun uniqueIn(dir: File, name: String, isDir: Boolean): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = if (isDir) name else name.removeSuffix(".html")
        val ext = if (isDir) "" else ".html"
        var i = 2
        f = File(dir, "$base ($i)$ext")
        while (f.exists()) {
            i++
            f = File(dir, "$base ($i)$ext")
        }
        return f
    }

    private fun clean(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
