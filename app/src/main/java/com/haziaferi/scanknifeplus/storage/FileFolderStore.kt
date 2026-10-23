package com.haziaferi.scanknifeplus.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [FolderStore] over a plain folder, for tests on the JVM (and any folder the app can reach as a File). Ids are paths relative to [root]
 * with `/` separators, the root being "". Taken names get Android's `name (1).ext` treatment, so callers see what a SAF provider would do.
 */
class FileFolderStore(private val root: File) : FolderStore {
    override val location: String = root.absolutePath

    override val rootId: String = ""

    override fun isAvailable(): Boolean = root.isDirectory && root.canWrite()

    override fun list(folderId: String): List<FolderEntry> {
        val children = fileOf(folderId).listFiles() ?: throw IOException("Cannot list $folderId")
        return children.map { entryOf(childId(folderId, it.name), it) }
    }

    override fun createFolder(parentId: String, name: String): FolderEntry {
        val parent = fileOf(parentId)
        repeat(MAX_TRIES) { n ->
            val candidate = File(parent, uniqueName(name, n, isFolder = true))
            if (candidate.mkdir()) return entryOf(childId(parentId, candidate.name), candidate)
            if (!parent.isDirectory) throw IOException("No folder $parentId")
        }
        throw IOException("No free name for $name in $parentId")
    }

    override fun createFile(parentId: String, name: String): FolderEntry {
        val parent = fileOf(parentId)
        if (!parent.isDirectory) throw IOException("No folder $parentId")
        repeat(MAX_TRIES) { n ->
            val candidate = File(parent, uniqueName(name, n, isFolder = false))
            if (candidate.createNewFile()) return entryOf(childId(parentId, candidate.name), candidate)
        }
        throw IOException("No free name for $name in $parentId")
    }

    override fun openRead(id: String): InputStream = FileInputStream(fileOf(id))

    override fun openWrite(id: String): OutputStream = object : FileOutputStream(fileOf(id)) {
        private var closed = false

        // Synced on close like SafFolderStore's streams, so tests run the same order of events.
        override fun close() {
            if (closed) return
            closed = true
            fd.sync()
            super.close()
        }
    }

    override fun sizeOf(id: String): Long = fileOf(id).let { if (it.isFile) it.length() else throw IOException("No file $id") }

    override fun rename(id: String, name: String): FolderEntry {
        val file = fileOf(id)
        val parent = file.parentFile ?: throw IOException("Cannot rename the root")
        if (!file.exists()) throw IOException("No entry $id")
        val parentId = id.substringBeforeLast('/', "")
        repeat(MAX_TRIES) { n ->
            val candidate = File(parent, uniqueName(name, n, file.isDirectory))
            // The exists() check and the rename are not atomic, which is fine for tests; SAF providers pick the free name themselves.
            if (!candidate.exists()) {
                if (!file.renameTo(candidate)) throw IOException("Cannot rename $id to ${candidate.name}")
                return entryOf(childId(parentId, candidate.name), candidate)
            }
        }
        throw IOException("No free name for $name")
    }

    override fun delete(id: String) {
        val file = fileOf(id)
        if (id.isEmpty() || !file.deleteRecursively()) throw IOException("Cannot delete $id")
    }

    private fun fileOf(id: String): File {
        val parts = id.split('/').filter { it.isNotEmpty() }
        require(parts.none { it == "." || it == ".." || '\\' in it }) { "Unsafe id: $id" }
        return parts.fold(root) { dir, part -> File(dir, part) }
    }

    private fun childId(parentId: String, name: String) = if (parentId.isEmpty()) name else "$parentId/$name"

    private fun entryOf(id: String, file: File) =
        FolderEntry(id, file.name, file.isDirectory, if (file.isFile) file.length() else 0, file.lastModified())

    private companion object {
        const val MAX_TRIES = 100

        /** [name] for n = 0, else `name (n).ext` (`name (n)` for a folder), as Android's FileUtils.buildUniqueFile names a taken file. */
        fun uniqueName(name: String, n: Int, isFolder: Boolean): String {
            if (n == 0) return name
            val dot = if (isFolder) -1 else name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
        }
    }
}
