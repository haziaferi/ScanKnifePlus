package com.haziaferi.scanknifeplus.scan.library

import com.haziaferi.scanknifeplus.scan.capture.CaptureStore
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.json.JSONException
import org.json.JSONObject

/** Stores a capture into the given files; [CaptureStore.store] in the app, a fake in tests. */
fun interface CaptureWriter {
    fun store(source: ImageSource, quad: Quad?, pageDest: File, originalDest: File?): StoredCapture?
}

/**
 * The scan library: one folder per document under [root], each holding its page images and a `document.json` record written atomically. Plain
 * files and JSON rather than OpenScan's SQLite database, so there is no schema or migration and a document is self-contained on disk.
 *
 * Every method is blocking and serialized on this instance: call off the main thread, and share one instance per library.
 */
class ScanLibrary(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeZone: TimeZone = TimeZone.getDefault(),
    private val capture: CaptureWriter = CaptureWriter(CaptureStore::store),
) {
    private var lastStamp = 0L

    /** All readable documents, newest first. A folder without a readable record is skipped rather than failing the whole library. */
    @Synchronized
    fun documents(): List<ScanDocument> =
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { read(it) }.sortedByDescending { it.created }

    @Synchronized
    fun document(id: String): ScanDocument? = folderOf(id)?.let { read(it) }

    /** Creates an empty document. Its folder name is [defaultName] for the current time, made unique if a document already has it. */
    @Synchronized
    fun create(name: String? = null): ScanDocument {
        val now = clock()
        var id = defaultName(now, timeZone)
        var n = 2
        while (File(root, id).exists()) id = "${defaultName(now, timeZone)}-${n++}"
        val doc = ScanDocument(id, name?.trim()?.takeIf { it.isNotEmpty() }, now, now, emptyList())
        File(root, id).mkdirs()
        write(doc)
        return doc
    }

    /**
     * Stores a capture or picked image as a new last page of document [id]: the page cropped to [quad] (fractional portrait coordinates, or null
     * for the whole image) and, if [keepOriginal], the uncropped original. Returns the updated document, or null if the document does not exist
     * or no page could be stored (nothing is left behind).
     */
    @Synchronized
    fun addCapture(id: String, source: ImageSource, quad: Quad?, keepOriginal: Boolean): ScanDocument? {
        val doc = document(id) ?: return null
        val folder = File(root, id)
        val stamp = nextStamp()
        val pageFile = File(folder, "$stamp.jpg")
        val originalFile = if (keepOriginal) File(folder, "orig_$stamp.jpg") else null
        val stored = capture.store(source, quad, pageFile, originalFile)
        if (stored == null) {
            pageFile.delete()
            originalFile?.delete()
            return null
        }
        val page = ScanPage("p$stamp", pageFile.name, stored.original?.name)
        return update(doc.copy(pages = doc.pages + page))
    }

    /** Renames document [id]; a blank [name] clears it, so the generated name shows again. The folder itself is never renamed. */
    @Synchronized
    fun rename(id: String, name: String?): ScanDocument? = document(id)?.let { update(it.copy(name = name?.trim()?.takeIf { n -> n.isNotEmpty() })) }

    /** Moves the page at [from] to [to] (both 0-based); out-of-range indices change nothing and return null. */
    @Synchronized
    fun movePage(id: String, from: Int, to: Int): ScanDocument? {
        val doc = document(id) ?: return null
        if (from !in doc.pages.indices || to !in doc.pages.indices) return null
        if (from == to) return doc
        val pages = doc.pages.toMutableList().apply { add(to, removeAt(from)) }
        return update(doc.copy(pages = pages))
    }

    /**
     * Deletes page [pageId] and every file it owns. Deleting the last page deletes the document too, as OpenScan does; then null is returned,
     * as it is for an unknown document or page.
     */
    @Synchronized
    fun deletePage(id: String, pageId: String): ScanDocument? {
        val doc = document(id) ?: return null
        val page = doc.pages.firstOrNull { it.id == pageId } ?: return null
        val remaining = doc.pages - page
        if (remaining.isEmpty()) {
            delete(id)
            return null
        }
        // The record is updated first, so a crash in between leaves stray files rather than a page pointing at nothing.
        val updated = update(doc.copy(pages = remaining))
        page.files.forEach { File(root, "$id/$it").delete() }
        return updated
    }

    /** Deletes document [id] with all its files; returns false if it did not exist or could not be fully deleted. */
    @Synchronized
    fun delete(id: String): Boolean = folderOf(id)?.deleteRecursively() ?: false

    /** The file behind [name] in document [id]. */
    fun file(id: String, name: String): File = File(File(root, id), name)

    private fun folderOf(id: String): File? {
        // Ids are folder names; anything that could leave the library root is refused.
        if (id.isEmpty() || id.contains('/') || id.contains('\\') || id == "." || id == "..") return null
        return File(root, id).takeIf { it.isDirectory }
    }

    private fun read(folder: File): ScanDocument? = try {
        val doc = ScanDocument.fromJson(JSONObject(File(folder, RECORD).readText()))
        // A record that names another folder was copied in by hand; its folder name is what identifies it.
        if (doc.id != folder.name) doc.copy(id = folder.name) else doc
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        null
    }

    private fun update(doc: ScanDocument): ScanDocument = doc.copy(modified = clock()).also { write(it) }

    /** Writes the record beside its final name and renames it into place, so a crash never leaves a half-written record. */
    private fun write(doc: ScanDocument) {
        val folder = File(root, doc.id)
        val tmp = File(folder, "$RECORD.tmp")
        val record = File(folder, RECORD)
        tmp.writeText(doc.toJson().toString(2))
        // On Android rename(2) replaces the old record atomically. Only filesystems that refuse to rename over a file (a Windows host running
        // the unit tests) reach the fallback, which gives up atomicity there.
        if (!tmp.renameTo(record) && !(record.delete() && tmp.renameTo(record))) {
            tmp.delete()
            throw IOException("Could not write the record of ${doc.id}")
        }
    }

    /** A per-library increasing stamp in microseconds, so page files never collide even when two land in the same millisecond. */
    private fun nextStamp(): Long {
        lastStamp = maxOf(lastStamp + 1, clock() * 1000)
        return lastStamp
    }

    companion object {
        const val RECORD = "document.json"
        const val NAME_PREFIX = "ScanKnife"

        /**
         * The name a document gets when it is created, `ScanKnife-2026-10-10-<epoch ms>`: OpenScan's scheme with this app's name. The date is
         * there to be read and the epoch stamp to be unique, and the whole name is safe on every filesystem (no spaces or colons).
         */
        fun defaultName(epochMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
            val c = Calendar.getInstance(timeZone).apply { timeInMillis = epochMillis }
            // Locale.ROOT: some locales would otherwise format the digits in another script.
            return String.format(Locale.ROOT, "%s-%04d-%02d-%02d-%d", NAME_PREFIX, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), epochMillis)
        }
    }
}
