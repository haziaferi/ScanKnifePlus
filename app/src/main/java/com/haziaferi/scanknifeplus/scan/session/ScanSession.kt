// Ported from OpenScan lib/view/screens/live_scan/live_scan_screen.dart (_onCapturePressed, _prepareCapture, _onDonePressed,
// _onUndoLastPressed, _onImportPressed) and lib/logic/cubit/directory_cubit.dart (createImage, _storePending).
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scan.session

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.cv.BookSplit
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a page of a session came from: the camera's shutter or a gallery pick. */
enum class ShotOrigin { CAMERA, IMPORT }

/**
 * A shot or picked image that could not be stored (OpenScan's "couldn't capture"), kept with its file until it is retried or dismissed, or the
 * session ends and drops both; [seq] grows by one per shot and per retry, so a UI can tell new ones.
 */
data class ShotFailure(val seq: Long, val origin: ShotOrigin)

/**
 * What a scan session shows: the document it fills ([documentId] is null until a new document gets its first page), the ids of the pages
 * this session stored and still keeps, in the order they joined the document (shutter order, with a retried shot after the shots before its
 * retry, and a book spread's left page before its right), the pages still waiting to be stored (two per book spread), and the failures not
 * yet retried or dismissed (none once the session has [finished]).
 */
data class ScanSessionState(
    val documentId: String? = null,
    val pages: List<String> = emptyList(),
    val pending: Int = 0,
    val failures: List<ShotFailure> = emptyList(),
    val finished: Boolean = false,
) {
    /** The pages this session has so far, stored or on their way: OpenScan's page counter and what Done is enabled by. */
    val count: Int get() = pages.size + pending
}

/** How a session was ended: [ScanSession.finish] keeps its pages, [ScanSession.cancel] (and [ScanSession.close]) takes them back. */
enum class SessionEnd { FINISHED, CANCELLED }

/**
 * How a session ended: the document it filled (null if it holds nothing from a new document), how many pages it kept there, and which ending
 * was applied ([end] is the first ending asked for, whichever call returned this).
 */
data class ScanSessionResult(val documentId: String?, val pagesAdded: Int, val end: SessionEnd)

/**
 * One scan session, the non-visual part of OpenScan's live-scan screen: camera shots and gallery picks are stored in the order handed in, on a
 * background thread, as new last pages of an existing document ([documentId]) or of one created with the first stored page. Unlike OpenScan the
 * shutter never waits for encoding, and each page is in the document as soon as it is stored. All methods are thread-safe, and a session must
 * be ended ([close] suits `use {}`).
 */
class ScanSession(
    private val library: ScanLibrary,
    /** This session's own staging folder: the camera writes shots here, and it is deleted with everything in it when the session ends. */
    val shotDir: File,
    documentId: String? = null,
    private val keepOriginal: Boolean = true,
    private val defaultFilter: String? = null,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "scan-session").apply { isDaemon = true } },
) : AutoCloseable {
    /** How a book spread is split into its two pages; see [captureBook]. */
    private class Book(val split: Double, val quarterTurns: Int)

    /**
     * One shot in the queue, which becomes one page, or two for a [book] spread. Fields are guarded by [lock]; [pageIds] is set by the worker
     * once the shot is stored.
     */
    private class Entry(var seq: Long, val origin: ShotOrigin, val source: ImageSource, val quad: Quad?, val shot: File?, val book: Book?) {
        var pageIds: List<String> = emptyList()
        var done = false
        var undone = false
        var failed = false
    }

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private var nextSeq = 0L

    /** How the session was ended, once [finish] or [cancel] was first called; guarded by [lock]. */
    private var ending: SessionEnd? = null

    /** The session's document; written by the worker only (and by the ending, after the worker stopped). Null until a new document is made. */
    @Volatile private var docId: String? = documentId

    /** Whether this session created [docId], and so may delete it when it ends up empty. */
    @Volatile private var created = false

    private val _state = MutableStateFlow(ScanSessionState(documentId = documentId))

    /** The session as it stands; updated from the worker thread. */
    val state: StateFlow<ScanSessionState> = _state.asStateFlow()

    init {
        shotDir.mkdirs()
        synchronized(ACTIVE) { ACTIVE += shotDir.absoluteFile }
    }

    /**
     * Queues camera shot [shot] (a JPEG the camera wrote into [shotDir]) to be stored cropped to [quad] (fractional portrait coordinates, null
     * for the whole image). Returns at once; the shot file is deleted once stored or undone, or at once (returning false) if the session has
     * already ended. A shot that cannot be stored shows up in [ScanSessionState.failures] and keeps its file until [dismiss] or the session ends.
     */
    fun capture(shot: File, quad: Quad?): Boolean = enqueue(ShotOrigin.CAMERA, ImageSource.of(shot), quad, shot, null)

    /**
     * Book mode: queues camera shot [shot] of an open book to be stored as its two facing pages, left then right, both or neither, as one shot
     * that [undoLast], [retry] and [dismiss] treat whole. [spread] (fractional portrait coordinates, null for the whole image) is split
     * [split] of the way along its top and bottom edges, with the book upright once the photo is turned clockwise by [quarterTurns]; see
     * ScanLibrary.addBookCapture. Otherwise as [capture]. Throws IllegalArgumentException for a [split] outside 0 to 1.
     */
    fun captureBook(shot: File, spread: Quad?, split: Double = BookSplit.DEFAULT_SPLIT, quarterTurns: Int = 0): Boolean {
        require(split in 0.0..1.0) { "Split outside the spread: $split" }
        return enqueue(ShotOrigin.CAMERA, ImageSource.of(shot), spread, shot, Book(split, quarterTurns))
    }

    /** Queues gallery picks [uris], read through [resolver], as whole-image pages (OpenScan's imported pages have no quad). */
    fun import(resolver: ContentResolver, uris: List<Uri>): Boolean = uris.all { import(ImageSource.of(resolver, it)) }

    /** Queues one picked image as a whole-image page; false if the session has already ended. */
    fun import(source: ImageSource): Boolean = enqueue(ShotOrigin.IMPORT, source, null, null, null)

    private fun enqueue(origin: ShotOrigin, source: ImageSource, quad: Quad?, shot: File?, book: Book?): Boolean {
        synchronized(lock) {
            if (ending != null) {
                shot?.delete()
                return false
            }
            val entry = Entry(nextSeq++, origin, source, quad, shot, book)
            entries += entry
            // Submitted under the lock, so the worker sees entries in exactly the order they joined the list.
            worker.execute { process(entry) }
            publish()
        }
        return true
    }

    /**
     * Stores failed shot [failure] again (OpenScan has no retry: its failed shots are lost), as the newest shot of the session: its page is
     * added after the pages stored so far and it is the next one [undoLast] takes back. If it fails again it comes back with a new seq. False if
     * [failure] is not a current failure or the session has ended.
     */
    fun retry(failure: ShotFailure): Boolean = synchronized(lock) {
        val entry = failed(failure) ?: return false
        entry.failed = false
        entry.done = false
        entry.seq = nextSeq++
        entries -= entry
        entries += entry
        worker.execute { process(entry) }
        publish()
        true
    }

    /**
     * Gives up on failed shot [failure]: deletes its camera shot and drops it from [state]. False if it is not a current failure or the session
     * has ended, which deletes every failed shot itself.
     */
    fun dismiss(failure: ShotFailure): Boolean = synchronized(lock) {
        val entry = failed(failure) ?: return false
        entries -= entry
        // Deleted before the state that drops it is published, as in process().
        entry.shot?.delete()
        publish()
        true
    }

    /** The entry of current failure [failure], or null once the session is ending; callers hold [lock]. */
    private fun failed(failure: ShotFailure): Entry? =
        if (ending != null) null else entries.firstOrNull { it.failed && it.seq == failure.seq && it.origin == failure.origin }

    /**
     * Takes back the most recent page of this session, as OpenScan's undo does: a shot still waiting is dropped, one being stored is removed as
     * soon as it is, and a stored page is deleted from the document in the background (if that delete fails, the page comes back into
     * [state]). Pages the document had before the session are never touched. Returns false if the session has no page to take back.
     */
    fun undoLast(): Boolean = synchronized(lock) {
        if (ending != null) return false
        val entry = entries.lastOrNull { !it.undone && (!it.done || it.pageIds.isNotEmpty()) } ?: return false
        entry.undone = true
        // A stored page is deleted on the worker, behind anything already queued; a pending one is dealt with by its own task.
        if (entry.done) worker.execute { removeUndone(entry) }
        publish()
        true
    }

    /**
     * Ends the session (Done): waits for every queued shot, deletes [shotDir] and the shots of failures and, if this session created it and it
     * has no page, the document, and returns the document with the number of pages kept. Later shots are refused, and an earlier ending stands.
     * Blocking.
     */
    @WorkerThread
    fun finish(): ScanSessionResult = end(SessionEnd.FINISHED)

    /**
     * Ends the session without keeping it (back without Done): as in OpenScan, every page this session stored is removed again and a document it
     * created is deleted, while an existing document keeps its earlier pages. An earlier ending stands. Blocking.
     */
    @WorkerThread
    fun cancel(): ScanSessionResult = end(SessionEnd.CANCELLED)

    /** [cancel] unless the session was already ended; a no-op then. Blocking. */
    @WorkerThread
    override fun close() {
        end(SessionEnd.CANCELLED)
    }

    private fun end(asked: SessionEnd): ScanSessionResult {
        val applied = synchronized(lock) {
            ending ?: asked.also {
                ending = it
                if (it == SessionEnd.CANCELLED) {
                    entries.forEach { e -> e.undone = true }
                    // Runs after every queued shot, each of which removes its own page once undone; this takes the ones stored before.
                    worker.execute { synchronized(lock) { entries.filter { e -> e.done } }.forEach { e -> removeUndone(e) } }
                }
                worker.shutdown()
            }
        }
        worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        // Failed shots are kept for a retry until now; one the camera wrote outside the session folder would outlive it.
        synchronized(lock) { entries.mapNotNull { it.shot } }.forEach { it.delete() }
        shotDir.deleteRecursively()
        synchronized(ACTIVE) { ACTIVE -= shotDir.absoluteFile }
        val id = docId
        var doc = id?.let { library.document(it) }
        if (doc != null && created && doc.pages.isEmpty()) {
            library.delete(doc.id)
            doc = null
        }
        docId = doc?.id
        return synchronized(lock) {
            val kept = entries.filter { !it.undone }.sumOf { it.pageIds.size }
            publish(finished = true)
            ScanSessionResult(doc?.id, if (doc == null) 0 else kept, applied)
        }
    }

    /** Runs on the worker: stores [entry] unless it was undone first, then gets rid of the shot file unless the store failed. */
    private fun process(entry: Entry) {
        val skip = synchronized(lock) { entry.undone }
        var pageIds = emptyList<String>()
        if (!skip) {
            // Any exception (create() throws when no document can be made) is a failed shot; a task that threw would leave it pending for good.
            pageIds = try {
                store(entry)
            } catch (e: Exception) {
                emptyList()
            }
        }
        val undone = synchronized(lock) {
            entry.pageIds = pageIds
            entry.done = true
            // A shot undone while it was being stored is no failure, whatever became of it.
            entry.failed = pageIds.isEmpty() && !entry.undone
            // Deleted before the state that drops it from pending is published, so whoever sees that state never finds the shot still there.
            if (!entry.failed) entry.shot?.delete()
            publish()
            entry.undone
        }
        // Undone while it was being stored: the pages were added all the same, and go again now.
        if (pageIds.isNotEmpty() && undone) removeUndone(entry)
    }

    /**
     * Stores [entry] as new last pages of the session's document, creating that document on the first page; returns the ids of the new pages
     * that are in the document, empty if none could be stored.
     */
    private fun store(entry: Entry): List<String> {
        val id = docId ?: library.create().id.also {
            docId = it
            created = true
        }
        val book = entry.book
        val (doc, pageIds) = if (book == null) {
            library.addCapture(id, entry.source, entry.quad, keepOriginal, defaultFilter)?.let { it.document to listOf(it.pageId) }
        } else {
            library.addBookCapture(id, entry.source, entry.quad, book.split, book.quarterTurns, keepOriginal, defaultFilter)
                ?.let { it.document to it.pageIds }
        } ?: return emptyList()
        return pageIds.filter { pageId -> doc.pages.any { it.id == pageId } }
    }

    /**
     * Runs on the worker: deletes the pages of an undone [entry]. The document is always kept (an empty one this session created goes when the
     * session ends). A page is forgotten only once it is verifiably gone; if a delete failed, the entry counts as kept again, with the pages
     * that are still there.
     */
    private fun removeUndone(entry: Entry) {
        val pageIds = synchronized(lock) { entry.pageIds }
        if (pageIds.isEmpty()) return
        val id = docId ?: return
        for (pageId in pageIds) {
            try {
                library.deletePage(id, pageId, keepDocument = true)
            } catch (e: IOException) {
                // Checked below: the record could not be written, so the page is still there.
            }
        }
        val present = library.document(id)?.pages.orEmpty().map { it.id }
        val remaining = pageIds.filter { it in present }
        synchronized(lock) {
            entry.pageIds = remaining
            if (remaining.isNotEmpty()) entry.undone = false
            publish()
        }
    }

    /** Pushes the current state; callers hold [lock]. */
    private fun publish(finished: Boolean = _state.value.finished) {
        _state.value = ScanSessionState(
            documentId = docId,
            pages = entries.filter { !it.undone }.flatMap { it.pageIds },
            pending = entries.filter { !it.undone && !it.done }.sumOf { if (it.book != null) 2 else 1 },
            // An ended session has deleted the failed shots, so none can be retried or dismissed any more.
            failures = if (finished) emptyList() else entries.filter { it.failed }.map { ShotFailure(it.seq, it.origin) },
            finished = finished,
        )
    }

    companion object {
        /** Staging folders of the sessions running in this process; only the app's own process writes the app's cache. */
        private val ACTIVE = mutableSetOf<File>()

        /**
         * A session over the app's scan library, with a new staging folder under [ScanFiles.stagingDir]. Folders there that belong to no
         * running session (left behind by a session that was never ended, or by a killed process) are deleted first; loose files and running
         * sessions' folders are left alone. Null if the folder cannot be created.
         */
        @WorkerThread
        fun start(context: Context, documentId: String? = null, keepOriginal: Boolean = true, defaultFilter: String? = null): ScanSession? {
            val app = context.applicationContext
            val staging = ScanFiles.stagingDir(app)
            // Under the registry's lock, so no other start can sweep this folder between its creation and its registration.
            return synchronized(ACTIVE) {
                staging.listFiles().orEmpty()
                    .filter { it.isDirectory && it.name.startsWith(FOLDER_PREFIX) && it.absoluteFile !in ACTIVE }
                    .forEach { it.deleteRecursively() }
                val folder = ScanFiles.newFolder(staging, FOLDER_PREFIX) ?: return null
                ScanSession(ScanLibrary(ScanFiles.libraryDir(app)), folder, documentId, keepOriginal, defaultFilter)
            }
        }

        private const val FOLDER_PREFIX = "session-"
    }
}
