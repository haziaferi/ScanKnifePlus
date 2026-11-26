package com.haziaferi.scanknifeplus.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.haziaferi.scanknifeplus.scan.ScanFiles
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Where a saved file lives: [APP] in the app's own `saved/` folder, [FOLDER] in the user's folder, [ELSEWHERE] where the system picker put it. */
enum class SavedPlace { APP, FOLDER, ELSEWHERE }

/**
 * One file the app saved, as History lists it across restarts. [tool] is the tool's catalog id, `scan` for a scan, or empty when not known;
 * [size] is in bytes (null when its provider does not say) and [time] epoch ms. An [SavedPlace.APP] file is [file] in `saved/`; the others
 * are reached by [uri]. [scan] is the scan library id of a scan's PDF kept in the app, which the move into a folder writes back with its scan.
 */
data class SavedEntry(
    val id: String,
    val name: String,
    val tool: String,
    val size: Long?,
    val pages: Int,
    val time: Long,
    val place: SavedPlace,
    val file: String? = null,
    val uri: Uri? = null,
    val scan: String? = null,
)

/**
 * The files the app saved: those kept in the app, as files in [dir] (`files/saved/`, which uninstalling deletes and the FileProvider shares
 * as `saved/`), and an index of every saved file in [index], written whole as `.tmp` then renamed. A name in [dir] is never reused, so no
 * save overwrites another. Files saved elsewhere keep their persisted read grant while listed: only the newest [ELSEWHERE_CAP] are kept, and
 * a tree grant (the user's folder) is never released. Every method blocks and they run one at a time; [listen] hears each change.
 */
class SavedFiles(
    val dir: File,
    private val index: File,
    private val authority: String,
    private val resolver: ContentResolver,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var rows: MutableList<SavedEntry>? = null
    private val listeners = CopyOnWriteArrayList<(List<SavedEntry>) -> Unit>()

    /** True once a load found the index unreadable: it was set aside as `.bad` and the list started empty; the files in [dir] were kept. */
    @Volatile
    var damaged = false
        private set

    /** Calls [listener] with the whole list after each change, on the thread that made it; the returned function stops that. */
    fun listen(listener: (List<SavedEntry>) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** Every saved file, newest first. */
    @WorkerThread
    fun entries(): List<SavedEntry> = synchronized(LOCK) { loaded().toList() }

    /** The content URI of [file] in [dir], as the FileProvider serves it, for writing, opening and sharing. */
    fun uriOf(file: File): Uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority).appendPath(SHARED_NAME).appendPath(file.name).build()

    /** The file in [dir] that [uri] names, or null for any other URI. */
    fun fileOf(uri: Uri): File? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority != authority) return null
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != SHARED_NAME || !FolderNames.isSafe(segments[1])) return null
        return File(dir, segments[1])
    }

    /** The file of an [SavedPlace.APP] [entry], or null for one elsewhere. */
    fun fileOf(entry: SavedEntry): File? = entry.file?.takeIf { entry.place == SavedPlace.APP && FolderNames.isSafe(it) }?.let { File(dir, it) }

    /**
     * Creates a new empty file in [dir] for an output named [name], or `<base>-2<ext>` and so on when that name is taken by a file or a
     * listed entry (in any case), so nothing is ever written over. Null when it cannot be created.
     */
    @WorkerThread
    fun create(name: String): File? = synchronized(LOCK) {
        val wanted = name.replace('/', '_').replace('\\', '_').trimStart('.').ifBlank { FALLBACK_NAME }
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("No folder $dir")
            val taken = dir.list().orEmpty().mapTo(HashSet()) { it.lowercase() }
            loaded().mapNotNullTo(taken) { it.file?.lowercase() }
            repeat(MAX_TRIES) {
                val free = FolderNames.free(wanted) { it.lowercase() in taken }
                val file = File(dir, free)
                if (file.createNewFile()) return file
                taken += free.lowercase()
            }
            null
        } catch (e: IOException) {
            Log.w(TAG, "Cannot create $wanted", e)
            null
        }
    }

    /**
     * Lists the file a save wrote at [uri] as [name], made by [tool] with [pages] pages: kept in the app when [uri] is one of [dir]'s, in the
     * folder for a document of a folder tree, else elsewhere, whose persisted read grant (and write, where offered) is taken. A file listed
     * already is listed again as new. [scan] is kept for a scan's PDF kept in the app only.
     */
    @WorkerThread
    fun record(uri: Uri, name: String, tool: String, pages: Int = 0, scan: String? = null): SavedEntry {
        val file = fileOf(uri)
        val place = when {
            file != null -> SavedPlace.APP
            DocumentsContract.isTreeUri(uri) -> SavedPlace.FOLDER
            else -> SavedPlace.ELSEWHERE
        }
        if (place == SavedPlace.ELSEWHERE) takeGrant(uri)
        val size = if (file != null) file.length() else sizeOf(uri)
        val entry = SavedEntry(
            UUID.randomUUID().toString(), name, tool, size, pages, clock(), place, file?.name, uri.takeIf { file == null }, scan.takeIf { file != null }
        )
        val released = mutableListOf<Uri>()
        val list = synchronized(LOCK) {
            val all = loaded()
            all.removeAll { if (file != null) it.file == file.name && it.place == SavedPlace.APP else it.uri == uri }
            all.add(0, entry)
            released += cap(all)
            write(all)
            all.toList()
        }
        released.forEach(::releaseGrant)
        notify(list)
        return entry
    }

    /** [record] on the index's own background thread, in order, for a caller on the main thread. */
    fun recordLater(uri: Uri, name: String, tool: String, pages: Int = 0, scan: String? = null) {
        WRITER.execute {
            try {
                record(uri, name, tool, pages, scan)
            } catch (e: Exception) {
                Log.w(TAG, "Could not list $name", e)
            }
        }
    }

    /** Waits until every [recordLater] asked for so far has run. */
    @VisibleForTesting
    fun awaitWrites() {
        WRITER.submit {}.get()
    }

    /** Deletes the file of the [SavedPlace.APP] entry [id], then its row; false (and the row kept) when the file stays. */
    @WorkerThread
    fun delete(id: String): Boolean {
        val list = synchronized(LOCK) {
            val all = loaded()
            val entry = all.firstOrNull { it.id == id && it.place == SavedPlace.APP } ?: return false
            val file = fileOf(entry)
            if (file != null && file.exists() && !file.delete()) return false
            all.remove(entry)
            write(all)
            all.toList()
        }
        notify(list)
        return true
    }

    /**
     * The [SavedPlace.APP] entry [id] now has its copy in the user's folder at [folderUri] (the caller has checked it): its file is deleted
     * first, then the row says [SavedPlace.FOLDER]. Without [folderUri] the folder holds it under its scan (written back), and the row goes.
     */
    @WorkerThread
    fun moved(id: String, folderUri: Uri?) {
        val list = synchronized(LOCK) {
            val all = loaded()
            val at = all.indexOfFirst { it.id == id && it.place == SavedPlace.APP }
            if (at < 0) return
            val file = fileOf(all[at])
            if (file != null && file.exists() && !file.delete()) return
            if (folderUri == null) all.removeAt(at) else all[at] = all[at].copy(place = SavedPlace.FOLDER, file = null, uri = folderUri)
            cap(all)
            write(all)
            all.toList()
        }
        notify(list)
    }

    /** Takes the rows of files saved elsewhere off the list and releases their grants; the files stay where they are. Returns how many. */
    @WorkerThread
    fun clearList(): Int {
        val (gone, list) = synchronized(LOCK) {
            val all = loaded()
            val gone = all.filter { it.place == SavedPlace.ELSEWHERE }
            if (gone.isEmpty()) return 0
            all.removeAll(gone)
            write(all)
            gone to all.toList()
        }
        gone.mapNotNull { it.uri }.forEach(::releaseGrant)
        notify(list)
        return gone.size
    }

    /** Drops the rows of files saved elsewhere that [opens] can no longer open, releasing their grants. Checked without holding the list. */
    @WorkerThread
    fun prune(opens: (Uri) -> Boolean = ::canOpen): Int {
        val elsewhere = entries().filter { it.place == SavedPlace.ELSEWHERE && it.uri != null }
        val dead = elsewhere.filter { !opens(it.uri!!) }.map { it.id }.toSet()
        if (dead.isEmpty()) return 0
        val (gone, list) = synchronized(LOCK) {
            val all = loaded()
            val gone = all.filter { it.id in dead }
            all.removeAll(gone)
            write(all)
            gone to all.toList()
        }
        gone.mapNotNull { it.uri }.forEach(::releaseGrant)
        notify(list)
        return gone.size
    }

    /**
     * The launch check: a file in [dir] that no row names (left by an index that could not be read, or a save the app was closed during) is
     * listed again, unless it is empty; a persisted grant that no row holds is released, a tree grant aside. Returns how many files it listed.
     */
    @WorkerThread
    fun recover(): Int {
        val (adopted, list) = synchronized(LOCK) {
            val all = loaded()
            val named = all.mapNotNullTo(HashSet()) { it.file }
            val orphans = dir.listFiles().orEmpty().filter { it.isFile && it.name !in named && !FolderNames.isHidden(it.name) && it.length() > 0 }
            for (f in orphans) {
                all += SavedEntry(UUID.randomUUID().toString(), f.name, UNKNOWN_TOOL, f.length(), 0, f.lastModified(), SavedPlace.APP, f.name)
            }
            if (orphans.isNotEmpty()) {
                all.sortByDescending { it.time }
                write(all)
            }
            orphans.size to all.toList()
        }
        val listed = list.filter { it.place == SavedPlace.ELSEWHERE }.mapNotNullTo(HashSet()) { it.uri }
        for (p in resolver.persistedUriPermissions.toList()) {
            if (p.uri !in listed && !DocumentsContract.isTreeUri(p.uri)) releaseGrant(p.uri)
        }
        if (adopted > 0) notify(list)
        return adopted
    }

    /** Deletes [uri]'s file in [dir] if no row names it: an output a failed save created. False for any other URI. */
    @WorkerThread
    fun discard(uri: Uri): Boolean {
        val file = fileOf(uri) ?: return false
        synchronized(LOCK) {
            if (loaded().any { it.place == SavedPlace.APP && it.file == file.name }) return false
            return file.delete()
        }
    }

    // Everything below runs under LOCK.

    private fun loaded(): MutableList<SavedEntry> = rows ?: load().also { rows = it }

    /** The index as written; its `.tmp` when a write was cut between deleting the old and renaming the new (a host that cannot rename over). */
    private fun load(): MutableList<SavedEntry> {
        val tmp = File(index.path + FolderNames.TMP)
        val source = when {
            index.isFile -> index
            tmp.isFile -> tmp
            else -> return mutableListOf()
        }
        return try {
            parse(source.readText())
        } catch (e: Exception) {
            Log.w(TAG, "The index of saved files cannot be read; it is kept as .bad and the list starts again", e)
            damaged = true
            val bad = File(index.path + BAD)
            bad.delete()
            if (!source.renameTo(bad)) source.delete()
            mutableListOf()
        }
    }

    private fun parse(text: String): MutableList<SavedEntry> {
        val array = JSONObject(text).getJSONArray("entries")
        val list = mutableListOf<SavedEntry>()
        for (i in 0 until array.length()) {
            // A row this version cannot read is left out; the rest of the list stays.
            val o = array.optJSONObject(i) ?: continue
            val place = SavedPlace.entries.firstOrNull { it.name == o.optString("place") } ?: continue
            val file = o.optStringOrNull("file")
            val uri = o.optStringOrNull("uri")?.let(Uri::parse)
            if (place == SavedPlace.APP && (file == null || !FolderNames.isSafe(file)) || place != SavedPlace.APP && uri == null) continue
            list += SavedEntry(
                id = o.optStringOrNull("id") ?: UUID.randomUUID().toString(),
                name = o.optString("name"),
                tool = o.optString("tool"),
                size = if (o.has("size") && !o.isNull("size")) o.getLong("size") else null,
                pages = o.optInt("pages"),
                time = o.optLong("time"),
                place = place,
                file = file.takeIf { place == SavedPlace.APP },
                uri = uri.takeIf { place != SavedPlace.APP },
                scan = o.optStringOrNull("scan"),
            )
        }
        list.sortByDescending { it.time }
        return list
    }

    /** Writes [all] as the index: in full as `.tmp`, synced, then renamed into place. A failure is logged; the next write tries again. */
    private fun write(all: List<SavedEntry>) {
        val array = JSONArray()
        for (e in all) {
            array.put(
                JSONObject().put("id", e.id).put("name", e.name).put("tool", e.tool).put("size", e.size ?: JSONObject.NULL)
                    .put("pages", e.pages).put("time", e.time).put("place", e.place.name).put("file", e.file ?: JSONObject.NULL)
                    .put("uri", e.uri?.toString() ?: JSONObject.NULL).put("scan", e.scan ?: JSONObject.NULL)
            )
        }
        val bytes = JSONObject().put("version", VERSION).put("entries", array).toString().toByteArray(Charsets.UTF_8)
        val tmp = File(index.path + FolderNames.TMP)
        try {
            index.parentFile?.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            // rename(2) replaces on Android; a host filesystem that will not rename over a file (Windows, in unit tests) needs it removed first.
            if (!tmp.renameTo(index) && !(index.delete() && tmp.renameTo(index))) throw IOException("Cannot rename $tmp")
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the index of saved files", e)
        }
    }

    /** Keeps the newest [ELSEWHERE_CAP] rows saved elsewhere and in the folder; returns the URIs of the dropped ones saved elsewhere. */
    private fun cap(all: MutableList<SavedEntry>): List<Uri> {
        all.sortByDescending { it.time }
        val dropped = mutableListOf<SavedEntry>()
        for (place in listOf(SavedPlace.ELSEWHERE, SavedPlace.FOLDER)) dropped += all.filter { it.place == place }.drop(ELSEWHERE_CAP)
        all.removeAll(dropped.toSet())
        return dropped.filter { it.place == SavedPlace.ELSEWHERE }.mapNotNull { it.uri }
    }

    private fun notify(list: List<SavedEntry>) = listeners.forEach { it(list) }

    private fun takeGrant(uri: Uri) {
        for (flags in listOf(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
            try {
                resolver.takePersistableUriPermission(uri, flags)
                return
            } catch (e: SecurityException) {
                // not offered with these flags: try read alone, else the row stays until its file can no longer be opened
            }
        }
    }

    private fun releaseGrant(uri: Uri) {
        if (DocumentsContract.isTreeUri(uri)) return // the user's folder
        val held = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val flags = (if (held.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (held.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            resolver.releasePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // already gone
        }
    }

    private fun sizeOf(uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }?.takeIf { it > 0 }
    } catch (e: Exception) {
        null // the provider does not say; the row shows no size
    }

    /** Whether [uri] still names a file: its provider answers for it (with the grant still held), or a file:// path exists. */
    private fun canOpen(uri: Uri): Boolean = try {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            uri.path?.let { File(it).isFile } == true
        } else {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true
        }
    } catch (e: Exception) {
        false // a revoked grant or a document that is gone; providers throw anything from SecurityException to IllegalArgumentException
    }

    companion object {
        private const val TAG = "SavedFiles"
        private const val VERSION = 1
        private const val BAD = ".bad"
        private const val MAX_TRIES = 20
        private const val FALLBACK_NAME = "output"

        /** The folder in the app's files, and its name in the FileProvider's URIs (res/xml/scan_file_paths.xml). */
        const val SHARED_NAME = "saved"
        private const val INDEX_NAME = "saved-index.json"

        /** The tool of a file found in `saved/` without its row. */
        const val UNKNOWN_TOOL = ""

        /**
         * How many files saved elsewhere History keeps (and the same for rows in the folder): Android caps persisted grants per app (128
         * before Android 11, 512 after), and the user's folder holds one of them.
         */
        const val ELSEWHERE_CAP = 100

        /** One lock for every instance, as two over the same folder would race on its names and its index. */
        private val LOCK = Any()

        private val WRITER = Executors.newSingleThreadExecutor { Thread(it, "saved-files").apply { isDaemon = true } }

        private var shared: Pair<Context, SavedFiles>? = null

        /** The app's saved files, one instance per application. */
        fun of(context: Context): SavedFiles = synchronized(this) {
            val app = context.applicationContext
            shared?.takeIf { it.first === app }?.second ?: SavedFiles(
                File(app.filesDir, SHARED_NAME), File(app.filesDir, INDEX_NAME), ScanFiles.authority(app), app.contentResolver
            ).also { shared = app to it }
        }

        private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    }
}
