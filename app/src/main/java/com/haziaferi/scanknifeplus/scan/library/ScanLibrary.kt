package com.haziaferi.scanknifeplus.scan.library

import android.util.Log
import androidx.annotation.WorkerThread
import com.haziaferi.scanknifeplus.scan.capture.AndroidPageImages
import com.haziaferi.scanknifeplus.scan.capture.CaptureStore
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.filter.DocumentFilters
import com.haziaferi.scanknifeplus.scanner.store.StoredImage
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
 * What a crash leaves behind (stray page files, temp files, a folder whose record was never written) is removed by [sweep].
 *
 * Every change updates the document's modified time (OpenScan only touched it when pages were added). Every method is blocking: call off the
 * main thread. Writing a record can fail with IOException (for example when storage is full).
 */
class ScanLibrary(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val capture: CaptureWriter = CaptureWriter(CaptureStore::store),
    private val images: PageImages = AndroidPageImages,
) {
    /**
     * All readable documents, newest first. Folders without a readable record are skipped; leftover tombstones are swept away. The first call
     * for a library folder in each process runs [sweep] first, under the same global lock (so it holds up every other library call while it
     * walks the library), and lists the records the sweep read; a sweep that fails is logged and tried again on the next call.
     */
    @WorkerThread
    fun documents(): List<ScanDocument> = synchronized(LOCK) {
        val swept = if (root.absolutePath in SWEPT) null else firstSweep()
        val docs = swept ?: run {
            val folders = root.listFiles().orEmpty().filter { it.isDirectory }
            folders.filter { it.name.startsWith(TOMBSTONE) }.forEach { it.deleteRecursively() }
            folders.filter { !it.name.startsWith(".") }.mapNotNull { read(it) }
        }
        docs.sortedByDescending { it.created }
    }

    /** The sweep [documents] runs once per process; returns the documents it read, or null (and logs) if it failed. */
    private fun firstSweep(): List<ScanDocument>? = try {
        sweepAndRead().second.also { SWEPT += root.absolutePath }
    } catch (e: Exception) {
        Log.w(TAG, "Sweeping the library at $root failed", e)
        null
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
     * for the whole image) and, if [keepOriginal], the uncropped original. A [filter] other than Original is then applied, as OpenScan applies its
     * default filter to new pages; if that fails the page stays unfiltered. Returns the updated document, or null if the document does not exist,
     * no page could be stored, or the record could not be updated; in every null case the new files are removed again.
     */
    fun addCapture(id: String, source: ImageSource, quad: Quad?, keepOriginal: Boolean, filter: String? = null): ScanDocument? {
        val (added, pageId) = addPage(id, source, quad, keepOriginal) ?: return null
        if (filter == null || DocumentFilters.byName(filter) == DocumentFilters.default) return added
        return applyFilter(id, pageId, filter) ?: document(id)
    }

    private fun addPage(id: String, source: ImageSource, quad: Quad?, keepOriginal: Boolean): Pair<ScanDocument, String>? {
        val (pageFile, originalFile) = synchronized(LOCK) {
            val folder = folderOf(id)?.takeIf { read(it) != null } ?: return null
            var stamp = nextStamp()
            // A clock that went back can repeat a stamp from an earlier run; never overwrite a page that exists.
            while (File(folder, "$stamp.jpg").exists() || File(folder, "orig_$stamp.jpg").exists()) stamp = nextStamp()
            val page = File(folder, "$stamp.jpg").apply { createNewFile() } // reserved before the lock is released
            val original = if (keepOriginal) File(folder, "orig_$stamp.jpg") else null
            hold(listOfNotNull(page, original))
            page to original
        }
        fun discard(): Pair<ScanDocument, String>? {
            pageFile.delete()
            originalFile?.delete()
            return null
        }

        try {
            // The slow part (decode, warp, encode) runs without the lock, so listing and other documents are not held up.
            val stored = capture.store(source, quad, pageFile, originalFile) ?: return discard()

            return synchronized(LOCK) {
                val doc = document(id) ?: return discard() // deleted while the capture was being stored
                val page = ScanPage(id = "p${pageFile.nameWithoutExtension}", image = pageFile.name, original = stored.original?.name)
                try {
                    update(doc.copy(pages = doc.pages + page)) to page.id
                } catch (e: IOException) {
                    discard()
                }
            }
        } finally {
            release(listOfNotNull(pageFile, originalFile))
        }
    }

    /**
     * Applies [filterName] to page [pageId] without ever losing the unfiltered page, as OpenScan does: the first filter keeps the current image
     * as the page's unfiltered copy, every later filter is computed from that copy (so filters never compound), and Original (or null) restores
     * the copy. An unknown [filterName] means Original, as in OpenScan. Returns the updated document, the unchanged document when there is
     * nothing to do, or null if the page does not exist, the
     * filter could not be computed, or the page changed meanwhile; on null the page is left exactly as it was.
     */
    fun applyFilter(id: String, pageId: String, filterName: String?): ScanDocument? {
        val filter = DocumentFilters.byName(filterName)
        val (doc, page) = snapshot(id, pageId) ?: return null
        if ((page.filter ?: DocumentFilters.default.name) == filter.name) return doc
        if (filter == DocumentFilters.default) {
            val unfiltered = page.unfiltered ?: return doc
            return commit(id, page, page.copy(image = unfiltered, unfiltered = null, filter = null), emptyList(), listOf(page.image))
        }

        // The filtered page, plus (on a first filter) the copy that becomes the unfiltered page.
        val reserved = reserve(id, if (page.unfiltered == null) listOf("", "unfilt_") else listOf("")) ?: return null
        try {
            val filtered = reserved[0]
            val promoted = reserved.getOrNull(1)
            val source = if (promoted != null) {
                if (!copied(file(id, page.image), promoted)) return discard(reserved)
                promoted
            } else {
                file(id, page.unfiltered!!)
            }
            if (!images.filter(source, filter, filtered)) return discard(reserved)
            val edited = page.copy(image = filtered.name, unfiltered = promoted?.name ?: page.unfiltered, filter = filter.name)
            // The old filtered image goes; on a first filter that is the page itself, which lives on as the promoted copy.
            return commit(id, page, edited, reserved, listOf(page.image))
        } finally {
            release(reserved)
        }
    }

    /**
     * Re-crops page [pageId] to [quad] (fractions of the source image's upright width and height), turned clockwise by [quarterTurns], as
     * OpenScan's crop step does: the crop comes from the kept original when there is one (else the unfiltered copy, else the page), so repeated
     * crops never eat into an earlier one; a page with no original first gets one, made from its unfiltered image at the original cap; the new
     * page is fitted to the page cap and starts filter-free. Returns the updated document, or null if nothing changed. Where OpenScan falls back to
     * storing an unnormalized copy when normalizing fails, the whole re-crop fails here and the page stays as it was.
     */
    fun recropPage(id: String, pageId: String, quad: Quad, quarterTurns: Int = 0): ScanDocument? {
        val (_, page) = snapshot(id, pageId) ?: return null
        val reserved = reserve(id, if (page.original == null) listOf("", "orig_") else listOf("")) ?: return null
        try {
            val cropped = reserved[0]
            val promoted = reserved.getOrNull(1)
            if (promoted != null &&
                !images.normalize(file(id, page.unfiltered ?: page.image), promoted, StoredImage.ORIGINAL_MAX_EDGE, StoredImage.ORIGINAL_QUALITY)
            ) {
                return discard(reserved)
            }
            val source = file(id, page.original ?: page.unfiltered ?: page.image)
            if (!images.crop(source, quad, quarterTurns, cropped)) return discard(reserved)
            val edited = page.copy(image = cropped.name, original = page.original ?: promoted!!.name, unfiltered = null, filter = null)
            return commit(id, page, edited, reserved, listOfNotNull(page.image, page.unfiltered))
        } finally {
            release(reserved)
        }
    }

    /** The document and its page [pageId], read under the lock. */
    private fun snapshot(id: String, pageId: String): Pair<ScanDocument, ScanPage>? = synchronized(LOCK) {
        val doc = document(id) ?: return null
        val page = doc.pages.firstOrNull { it.id == pageId } ?: return null
        doc to page
    }

    /**
     * Reserves new empty files named `<prefix><stamp>.jpg` (one stamp for all) in document [id], so nothing else can take those names, and holds
     * them so [sweep] leaves them alone; the caller must [release] them once the record names them or they are discarded.
     */
    private fun reserve(id: String, prefixes: List<String>): List<File>? = synchronized(LOCK) {
        val folder = folderOf(id) ?: return null
        var stamp = nextStamp()
        // A clock that went back can repeat a stamp from an earlier run; never overwrite a file that exists.
        while (prefixes.any { File(folder, "$it$stamp.jpg").exists() }) stamp = nextStamp()
        val created = mutableListOf<File>()
        try {
            prefixes.forEach { created += File(folder, "$it$stamp.jpg").apply { createNewFile() } }
            hold(created)
            created
        } catch (e: IOException) {
            created.forEach { it.delete() }
            null
        }
    }

    /** Marks [files] as in use by an edit in progress, so [sweep] never removes them (or the `.tmp` files written beside them). */
    private fun hold(files: List<File>) = synchronized(LOCK) { files.forEach { HELD += heldKey(it.parentFile?.name, it.name) } }

    private fun release(files: List<File>) = synchronized(LOCK) { files.forEach { HELD -= heldKey(it.parentFile?.name, it.name) } }

    private fun discard(files: List<File>): ScanDocument? {
        files.forEach { it.delete() }
        return null
    }

    // Plain streams rather than File.copyTo, which creates missing parent folders and could bring back a document deleted meanwhile.
    private fun copied(from: File, to: File): Boolean = try {
        from.inputStream().use { input -> FileOutputStream(to).use { input.copyTo(it) } }
        true
    } catch (e: IOException) {
        false
    }

    /**
     * Replaces [before] with [after] in document [id] if the page is still exactly [before], then deletes the [obsolete] files [after] no longer
     * uses. If the page changed meanwhile, or the record cannot be written, the [created] files are removed and null is returned.
     */
    private fun commit(id: String, before: ScanPage, after: ScanPage, created: List<File>, obsolete: List<String>): ScanDocument? =
        synchronized(LOCK) {
            val doc = document(id)
            val index = doc?.pages?.indexOf(before) ?: -1
            if (doc == null || index < 0) return discard(created)
            val updated = try {
                update(doc.copy(pages = doc.pages.toMutableList().apply { set(index, after) }))
            } catch (e: IOException) {
                return discard(created)
            }
            // Deleted only after the record no longer names them, so a crash leaves stray files rather than a page pointing at nothing.
            obsolete.filter { it !in after.files }.forEach { file(id, it).delete() }
            updated
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
     * does, and returns null, as does an unknown document or page. If the document could not be deleted it is returned unchanged. With
     * [keepDocument] the document stays even when it is left empty (a scan session taking back its own pages must never delete a document it
     * did not create).
     */
    fun deletePage(id: String, pageId: String, keepDocument: Boolean = false): ScanDocument? = synchronized(LOCK) {
        val doc = document(id) ?: return null
        val page = doc.pages.firstOrNull { it.id == pageId } ?: return null
        val remaining = doc.pages - page
        if (remaining.isEmpty() && !keepDocument) return if (delete(id)) null else doc
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

    /**
     * Removes what a crash or a killed process can leave in the library, and returns how many files and folders it removed. Blocking, and holds
     * the library lock while it runs; [documents] runs it once per process, so the app does not need to call it.
     *
     * - Tombstones (documents whose deletion was interrupted) go whole.
     * - In a document with a readable record, a file goes only if it has a name the library makes (`<stamp>.jpg`, `orig_<stamp>.jpg`,
     *   `unfilt_<stamp>.jpg`, any of those with `.tmp`, or the record's temp file), the record does not name it, no edit in progress in this
     *   process holds it, and it was last modified more than a day ago. Other files and folders are never touched.
     * - A folder with no readable record goes only if it holds nothing but a record or record temp file that is empty, or was read in full and
     *   does not even start like JSON (what a crash during [create] can leave), and everything in it (for an empty folder, the folder) is more
     *   than a day old. A record that starts like JSON but does not parse is kept, with its folder: it may be damaged or from a newer app
     *   version. A folder with any page image, a record that could not be read (an I/O error may be transient), or anything else in it is
     *   kept, as are folders starting with a dot.
     *
     * Why edits in progress are safe: page files are only created under the lock (by [reserve], and by the reservation for a new page in
     * [addPage]), and are held from that moment until the record names them or they are deleted again; the sweep runs entirely under the same
     * lock, so it never sees a file that is reserved but not yet held, or a record half-way through being written ([create] makes its folder and
     * writes its record in one locked step). Holds live in memory, so they cannot protect an edit running in another process; there the age
     * limit does (an edit takes seconds), and it also keeps the sweep away from files whose modification time is in the future or unknown. A
     * crash in this process ends every hold with it, and leaves only files that no record will ever name.
     */
    @WorkerThread
    fun sweep(): Int = sweepAndRead().first

    /** [sweep], also returning the documents it read. */
    private fun sweepAndRead(): Pair<Int, List<ScanDocument>> = synchronized(LOCK) {
        val cutoff = clock() - STALE_MILLIS
        var removed = 0
        val docs = mutableListOf<ScanDocument>()
        for (folder in root.listFiles().orEmpty()) {
            if (!folder.isDirectory) continue
            if (folder.name.startsWith(TOMBSTONE)) {
                if (folder.deleteRecursively()) removed++
                continue
            }
            if (folder.name.startsWith(".")) continue
            val doc = read(folder)
            if (doc != null) {
                docs += doc
                removed += sweepDocument(folder, doc, cutoff)
            } else {
                removed += sweepUnrecorded(folder, cutoff)
            }
        }
        removed to docs
    }

    /** The file [name] in document [id]; throws IllegalArgumentException for an id or name that could point outside the document's folder. */
    fun file(id: String, name: String): File {
        require(isSafeName(id) && isSafeName(name)) { "Unsafe path: $id/$name" }
        return File(File(root, id), name)
    }

    // Callers hold LOCK for everything below.

    /** Removes the stale files of a readable document that its record does not name and no edit holds; see [sweep]. */
    private fun sweepDocument(folder: File, doc: ScanDocument, cutoff: Long): Int {
        val named = doc.pages.flatMapTo(HashSet()) { it.files }
        var removed = 0
        for (f in folder.listFiles().orEmpty()) {
            val name = f.name
            if (name != RECORD_TMP && !LIBRARY_FILE.matches(name)) continue // not a file the library makes
            if (name in named || heldKey(folder.name, name) in HELD) continue
            if (f.isFile && isStale(f, cutoff) && f.delete()) removed++
        }
        return removed
    }

    /** Removes a folder with no readable record if it is plainly what a crash during [create] leaves; see [sweep]. */
    private fun sweepUnrecorded(folder: File, cutoff: Long): Int {
        val entries = folder.listFiles() ?: return 0 // could not be listed: keep
        // An empty folder has only its own time to go by; otherwise the files' times count, as recovering a record touches the folder.
        if (entries.isEmpty() && !isStale(folder, cutoff)) return 0
        for (f in entries) {
            if ((f.name != RECORD && f.name != RECORD_TMP) || !f.isFile || !isStale(f, cutoff) || f.length() > MAX_RECORD_BYTES) return 0
            val bytes = try {
                f.readBytes()
            } catch (e: IOException) {
                return 0
            }
            if (!blankOrNotJson(bytes)) return 0
        }
        // One by one, then the folder only if it is empty by then, so nothing that appeared meanwhile goes with it.
        val deleted = entries.count { it.delete() }
        return deleted + if (folder.delete()) 1 else 0
    }

    /**
     * True if [bytes] are empty, only whitespace, or start with anything but `{`: never a record. A record that starts like JSON but does not
     * parse (damaged, or written by a newer app version before a downgrade) is not this, and its folder is kept.
     */
    private fun blankOrNotJson(bytes: ByteArray): Boolean {
        val first = bytes.firstOrNull { it.toInt().toChar() !in " \t\n\r" }
        return first?.toInt()?.toChar() != '{'
    }

    /** Last modified before [cutoff]; an unknown time (0, which is also what an I/O error gives) or one in the future is never stale. */
    private fun isStale(f: File, cutoff: Long): Boolean = f.lastModified().let { it > 0 && it < cutoff }

    private fun folderOf(id: String): File? = if (isSafeName(id) && !id.startsWith(".")) File(root, id).takeIf { it.isDirectory } else null

    private fun read(folder: File): ScanDocument? {
        val record = File(folder, RECORD)
        val tmp = File(folder, RECORD_TMP)
        // Only the temp file is left by a crash between removing an old record and renaming the new one into place, when it is complete (it was
        // synced first), or by a crash while create() writes the first record, when it may be partial and then fails to parse like any damage.
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
        // If even this rename fails, the new record survives as the synced temp file and read() recovers it: it counts as written, and callers
        // must not throw away the files it names.
        tmp.renameTo(record)
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

        /** How old an unnamed file must be before [sweep] removes it; a day, as for stale PDF export work folders. */
        internal const val STALE_MILLIS = 24L * 60 * 60 * 1000

        /** Names of the page files the library makes, and of the temp files written beside them while they are encoded. */
        private val LIBRARY_FILE = Regex("(orig_|unfilt_)?[0-9]+[.]jpg([.]tmp)?")
        const val NAME_PREFIX = "ScanKnife"
        private const val TAG = "ScanLibrary"

        /** One lock for every instance, since instances over the same folder would otherwise race on records and stamps. */
        private val LOCK = Any()

        /** Increasing microsecond stamps for page files, shared by all instances, so two never collide even within one millisecond. */
        private var lastStamp = 0L

        /** Page files (`<folder>/<name>`) held by edits in progress in this process, which [sweep] must not remove; guarded by [LOCK]. */
        private val HELD = HashSet<String>()

        /** Library folders [documents] has already swept in this process; guarded by [LOCK]. */
        private val SWEPT = HashSet<String>()

        /** The hold key of [name] in [folder]; a `.tmp` file written beside a held file counts as that file. */
        private fun heldKey(folder: String?, name: String) = "$folder/${name.removeSuffix(".tmp")}"

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
