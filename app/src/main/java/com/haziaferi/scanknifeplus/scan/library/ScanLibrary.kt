package com.haziaferi.scanknifeplus.scan.library

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
            page to if (keepOriginal) File(folder, "orig_$stamp.jpg") else null
        }
        fun discard(): Pair<ScanDocument, String>? {
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
                update(doc.copy(pages = doc.pages + page)) to page.id
            } catch (e: IOException) {
                discard()
            }
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
    }

    /** The document and its page [pageId], read under the lock. */
    private fun snapshot(id: String, pageId: String): Pair<ScanDocument, ScanPage>? = synchronized(LOCK) {
        val doc = document(id) ?: return null
        val page = doc.pages.firstOrNull { it.id == pageId } ?: return null
        doc to page
    }

    /** Reserves new empty files named `<prefix><stamp>.jpg` (one stamp for all) in document [id], so nothing else can take those names. */
    private fun reserve(id: String, prefixes: List<String>): List<File>? = synchronized(LOCK) {
        val folder = folderOf(id) ?: return null
        var stamp = nextStamp()
        // A clock that went back can repeat a stamp from an earlier run; never overwrite a file that exists.
        while (prefixes.any { File(folder, "$it$stamp.jpg").exists() }) stamp = nextStamp()
        val created = mutableListOf<File>()
        try {
            prefixes.forEach { created += File(folder, "$it$stamp.jpg").apply { createNewFile() } }
            created
        } catch (e: IOException) {
            created.forEach { it.delete() }
            null
        }
    }

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
