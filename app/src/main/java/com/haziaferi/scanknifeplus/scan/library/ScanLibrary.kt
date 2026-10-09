package com.haziaferi.scanknifeplus.scan.library

import com.haziaferi.scanknifeplus.scan.capture.CaptureStore
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/** Stores a capture into the given files; [CaptureStore.store] in the app, a fake in tests. */
fun interface CaptureWriter {
    fun store(source: ImageSource, quad: Quad?, pageDest: File, originalDest: File?): StoredCapture?
}

/**
 * The scan library: one folder per document under [root], each holding its page images and a `document.json` record. Plain files and JSON
 * rather than OpenScan's SQLite database, so there is no schema or migration and a document is self-contained on disk.
 *
 * Safety: records are synced to disk and renamed into place, and one left mid-write is recovered from its temp file; folders are claimed
 * atomically; a deleted document is first renamed to a tombstone, so it disappears whole; page file names read from a record must stay inside
 * their folder. All access, from any instance, goes through one process-wide lock, which is not held while a capture is decoded and encoded.
 *
 * Every change updates the document's modified time (OpenScan only touched it when pages were added). Every method is blocking: call off the
 * main thread. Writing a record can fail with IOException (for example when storage is full).
 */
class ScanLibrary(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val capture: CaptureWriter = CaptureWriter(CaptureStore::store),
) {
    /** All readable documents, newest first. Folders without a readable record are skipped; leftover tombstones are swept away. */
    fun documents(): List<ScanDocument> = synchronized(LOCK) {
        val folders = root.listFiles().orEmpty().filter { it.isDirectory }
        folders.filter { it.name.startsWith(TOMBSTONE) }.forEach { it.deleteRecursively() }
        folders.filter { !it.name.startsWith(".") }.mapNotNull { read(it) }.sortedByDescending { it.created }
    }

    fun document(id: String): ScanDocument? = synchronized(LOCK) { folderOf(id)?.let { read(it) } }

    /**
     * Creates an empty document named [defaultName] for the current time, made unique if a document already has it. It exists from now on, unlike
     * OpenScan's, which appeared with its first page: a caller whose first scan session stores no page should [delete] it.
     */
    fun create(name: String? = null): ScanDocument = synchronized(LOCK) {
        root.mkdirs()
        val now = clock()
        val base = defaultName(now, timeZone)
        var id = base
        var n = 2
        // mkdir is atomic and fails if the folder exists, so two creations can never share a folder.
        while (!File(root, id).mkdir()) {
            if (!root.isDirectory) throw IOException("Cannot create the library at $root")
            id = "$base-${n++}"
        }
        val doc = ScanDocument(id, name?.trim()?.takeIf { it.isNotEmpty() }, now, now, emptyList())
        try {
            write(doc)
        } catch (e: IOException) {
            File(root, id).deleteRecursively()
            throw e
        }
        doc
    }

    /**
     * Stores a capture or picked image as a new last page of document [id]: the page cropped to [quad] (fractional portrait coordinates, or null
     * for the whole image) and, if [keepOriginal], the uncropped original. Returns the updated document, or null if the document does not exist,
     * no page could be stored, or the record could not be updated; in every null case the new files are removed again.
     */
    fun addCapture(id: String, source: ImageSource, quad: Quad?, keepOriginal: Boolean): ScanDocument? {
        val (pageFile, originalFile) = synchronized(LOCK) {
            val folder = folderOf(id)?.takeIf { read(it) != null } ?: return null
            var stamp = nextStamp()
            // A clock that went back can repeat a stamp from an earlier run; never overwrite a page that exists.
            while (File(folder, "$stamp.jpg").exists() || File(folder, "orig_$stamp.jpg").exists()) stamp = nextStamp()
            val page = File(folder, "$stamp.jpg").apply { createNewFile() } // reserved before the lock is released
            page to if (keepOriginal) File(folder, "orig_$stamp.jpg") else null
        }
        fun discard(): ScanDocument? {
            pageFile.delete()
            originalFile?.delete()
            return null
        }

        // The slow part (decode, warp, encode) runs without the lock, so listing and other documents are not held up.
        val stored = capture.store(source, quad, pageFile, originalFile) ?: return discard()

        return synchronized(LOCK) {
            val doc = document(id) ?: return discard() // deleted while the capture was being stored
            val page = ScanPage(id = "p${pageFile.nameWithoutExtension}", image = pageFile.name, original = stored.original?.name)
            try {
                update(doc.copy(pages = doc.pages + page))
            } catch (e: IOException) {
                discard()
            }
        }
    }

    /** Renames document [id]; a blank [name] clears it, so the generated name shows again. The folder itself is never renamed. */
    fun rename(id: String, name: String?): ScanDocument? = synchronized(LOCK) {
        document(id)?.let { update(it.copy(name = name?.trim()?.takeIf { n -> n.isNotEmpty() })) }
    }

    /** Moves the page at [from] to [to] (both 0-based); out-of-range indices change nothing and return null. */
    fun movePage(id: String, from: Int, to: Int): ScanDocument? = synchronized(LOCK) {
        val doc = document(id) ?: return null
        if (from !in doc.pages.indices || to !in doc.pages.indices) return null
        if (from == to) return doc
        update(doc.copy(pages = doc.pages.toMutableList().apply { add(to, removeAt(from)) }))
    }

    /**
     * Deletes page [pageId] and every file it owns, returning the updated document. Deleting the last page deletes the document too, as OpenScan
     * does, and returns null, as does an unknown document or page. If the document could not be deleted it is returned unchanged.
     */
    fun deletePage(id: String, pageId: String): ScanDocument? = synchronized(LOCK) {
        val doc = document(id) ?: return null
        val page = doc.pages.firstOrNull { it.id == pageId } ?: return null
        val remaining = doc.pages - page
        if (remaining.isEmpty()) return if (delete(id)) null else doc
        // The record is updated first, so a crash in between leaves stray files rather than a page pointing at nothing.
        val updated = update(doc.copy(pages = remaining))
        page.files.forEach { file(id, it).delete() }
        updated
    }

    /**
     * Deletes document [id] with all its files. The folder is first renamed to a tombstone, which is atomic, so the document disappears whole even
     * if deleting its files is interrupted; [documents] sweeps leftover tombstones. Returns false if it did not exist or could not be removed.
     */
    fun delete(id: String): Boolean = synchronized(LOCK) {
        val folder = folderOf(id) ?: return false
        val tombstone = File(root, "$TOMBSTONE$id-${System.nanoTime()}")
        if (!folder.renameTo(tombstone)) return false
        tombstone.deleteRecursively()
        true
    }

    /** The file [name] in document [id]; throws IllegalArgumentException for an id or name that could point outside the document's folder. */
    fun file(id: String, name: String): File {
        require(isSafeName(id) && isSafeName(name)) { "Unsafe path: $id/$name" }
        return File(File(root, id), name)
    }

    // Callers hold LOCK for everything below.

    private fun folderOf(id: String): File? = if (isSafeName(id) && !id.startsWith(".")) File(root, id).takeIf { it.isDirectory } else null

    private fun read(folder: File): ScanDocument? {
        val record = File(folder, RECORD)
        val tmp = File(folder, RECORD_TMP)
        // A crash between removing an old record and renaming the new one into place leaves only the temp file, which is complete (it was synced).
        if (!record.exists() && tmp.exists()) tmp.renameTo(record)
        if (!record.isFile || record.length() > MAX_RECORD_BYTES) return null
        return try {
            val doc = ScanDocument.fromJson(JSONObject(record.readText()))
            if (doc.pages.any { p -> p.files.any { !isSafeName(it) || it == RECORD || it == RECORD_TMP } }) return null
            // A record that names another folder was copied in by hand; its folder name is what identifies it.
            if (doc.id != folder.name) doc.copy(id = folder.name) else doc
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null // absurdly nested JSON
        }
    }

    private fun update(doc: ScanDocument): ScanDocument = doc.copy(modified = clock()).also { write(it) }

    /** Writes and syncs the record beside its final name, then renames it into place, so a crash never leaves a half-written record. */
    private fun write(doc: ScanDocument) {
        val folder = File(root, doc.id)
        val tmp = File(folder, RECORD_TMP)
        val record = File(folder, RECORD)
        FileOutputStream(tmp).use { out ->
            out.write(doc.toJson().toString(2).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (tmp.renameTo(record)) return
        // rename(2) replaces the old record on Android; only a filesystem that refuses to rename over a file (a Windows host running the unit
        // tests) needs the old record removed first. Once it is gone the synced temp file is the only copy, so it is kept for read() to recover.
        if (!record.delete()) {
            tmp.delete()
            throw IOException("Could not replace the record of ${doc.id}")
        }
        if (!tmp.renameTo(record)) throw IOException("Could not write the record of ${doc.id}; it is kept as $RECORD_TMP")
    }

    private fun nextStamp(): Long {
        lastStamp = maxOf(lastStamp + 1, clock() * 1000)
        return lastStamp
    }

    companion object {
        const val RECORD = "document.json"
        private const val RECORD_TMP = "document.json.tmp"
        private const val TOMBSTONE = ".trash-"
        private const val MAX_RECORD_BYTES = 1L shl 20
        const val NAME_PREFIX = "ScanKnife"

        /** One lock for every instance, since instances over the same folder would otherwise race on records and stamps. */
        private val LOCK = Any()

        /** Increasing microsecond stamps for page files, shared by all instances, so two never collide even within one millisecond. */
        private var lastStamp = 0L

        /** A single path segment: not empty, not `.` or `..`, no separators. */
        private fun isSafeName(name: String) = name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name

        /**
         * The name a document gets when it is created, `ScanKnife-2026-10-10-<epoch ms>`: OpenScan's scheme with this app's name. The date is
         * there to be read and the epoch stamp to be unique, and the whole name is safe on every filesystem (no spaces or colons).
         */
        fun defaultName(epochMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
            // Gregorian whatever the locale (a Buddhist or Japanese calendar would change the year), and Locale.ROOT digits.
            val c = GregorianCalendar(timeZone, Locale.ROOT).apply { timeInMillis = epochMillis }
            return String.format(Locale.ROOT, "%s-%04d-%02d-%02d-%d", NAME_PREFIX, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), epochMillis)
        }
    }
}
