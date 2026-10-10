// Ported from OpenScan lib/view/screens/live_scan/live_scan_screen.dart (_onCapturePressed, _prepareCapture, _onDonePressed,
// _onUndoLastPressed, _onImportPressed) and lib/logic/cubit/directory_cubit.dart (createImage, _storePending).
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scan.session

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
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

/** A shot or picked image that could not be stored (OpenScan's "couldn't capture"); [seq] grows by one per shot, so a UI can tell new ones. */
data class ShotFailure(val seq: Long, val origin: ShotOrigin)

/**
 * What a scan session shows: the document it fills ([documentId] is null until a new document gets its first page), the ids of the pages
 * this session stored and still keeps, in shutter order, the shots still waiting to be stored, and every shot that could not be stored.
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

/** How a session ended: the document it filled (null if it holds nothing from a new document) and how many pages it kept there. */
data class ScanSessionResult(val documentId: String?, val pagesAdded: Int)

/**
 * One scan session: the non-visual part of OpenScan's live-scan screen, between the camera and the scan library. Camera shots ([capture]) and
 * gallery picks ([import]) join one queue and are stored, in the order they were handed in, on one background thread through
 * [ScanLibrary.addCapture], each as a new last page of the session's document with the session's [keepOriginal] and [defaultFilter].
 *
 * The session fills an existing document ([documentId] given: pages are added after the ones it has) or a new one, created when the first page
 * is stored, so a session that stores nothing leaves no document behind.
 *
 * Differences from OpenScan, each deliberate:
 *  - The shutter never waits for a page to be encoded. OpenScan's `_capturing` gate held the shutter, auto-capture and Done until the last shot
 *    was processed; here [capture] only queues, and the shots are worked off in the background in shutter order.
 *  - Pages go into the document as they are stored, not all at once when the screen is closed, so the document (and [state]) grows while the
 *    user frames the next page, and a page already stored survives the process being killed.
 *  - One attempt per shot: a shot that cannot be stored is reported in [ScanSessionState.failures] right away. OpenScan staged it raw, tried
 *    again when the document adopted it, and then skipped it silently.
 *  - [finish] clears only the staging folder ([ScanFiles.clearStaging]); OpenScan wiped the whole cache directory.
 *
 * Shot files: [ScanLibrary.addCapture] only reads its source, so the session deletes each camera shot itself once it is stored, has failed or
 * was undone; picked images are only read. If the process dies, shots still in the queue are lost (their files are swept by the next
 * [finish] or [cancel], which clear the whole staging folder); pages stored before that stay in the document.
 *
 * [capture], [import] and [undoLast] return at once and may be called from the main thread; [finish] and [cancel] block until the queue is
 * done. All methods are thread-safe.
 */
class ScanSession(
    private val library: ScanLibrary,
    documentId: String? = null,
    private val keepOriginal: Boolean = true,
    private val defaultFilter: String? = null,
    private val clearStaging: () -> Boolean = { true },
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "scan-session").apply { isDaemon = true } },
) {
    /** One shot in the queue. Fields are guarded by [lock]; [pageId] is set by the worker once the page is stored. */
    private class Entry(val seq: Long, val origin: ShotOrigin, val source: ImageSource, val quad: Quad?, val shot: File?) {
        var pageId: String? = null
        var done = false
        var undone = false
    }

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private var nextSeq = 0L
    private var closed = false

    /** The session's document; written by the worker only. Null until a new document stores its first page. */
    @Volatile private var docId: String? = documentId

    /** Whether this session created [docId], and so may delete it when it ends up empty. */
    @Volatile private var created = false

    private val failures = mutableListOf<ShotFailure>()
    private val _state = MutableStateFlow(ScanSessionState(documentId = documentId))

    /** The session as it stands; updated from the worker thread. */
    val state: StateFlow<ScanSessionState> = _state.asStateFlow()

    /**
     * Queues camera shot [shot] (a JPEG written into the staging folder) to be stored cropped to [quad] (fractional portrait coordinates, null
     * for the whole image). Returns at once; false if the session has already ended, in which case the shot file is deleted.
     */
    fun capture(shot: File, quad: Quad?): Boolean = enqueue(ShotOrigin.CAMERA, ImageSource.of(shot), quad, shot)

    /** Queues gallery picks [uris], read through [resolver], as whole-image pages (OpenScan's imported pages have no quad). */
    fun import(resolver: ContentResolver, uris: List<Uri>): Boolean = uris.all { import(ImageSource.of(resolver, it)) }

    /** Queues one picked image as a whole-image page; false if the session has already ended. */
    fun import(source: ImageSource): Boolean = enqueue(ShotOrigin.IMPORT, source, null, null)

    private fun enqueue(origin: ShotOrigin, source: ImageSource, quad: Quad?, shot: File?): Boolean {
        synchronized(lock) {
            if (closed) {
                shot?.delete()
                return false
            }
            val entry = Entry(nextSeq++, origin, source, quad, shot)
            entries += entry
            // Submitted under the lock, so the worker sees entries in exactly the order they joined the list.
            worker.execute { process(entry) }
            publish()
        }
        return true
    }

    /**
     * Takes back the most recent page of this session, as OpenScan's undo does: a shot still waiting is dropped, one being stored is removed as
     * soon as it is, and a stored page is deleted from the document in the background. Pages the document had before the session are never
     * touched. Returns false if the session has no page to take back.
     */
    fun undoLast(): Boolean = synchronized(lock) {
        if (closed) return false
        val entry = entries.lastOrNull { !it.undone && (!it.done || it.pageId != null) } ?: return false
        entry.undone = true
        // A stored page is deleted on the worker, behind anything already queued; a pending one is dealt with by its own task.
        if (entry.done) worker.execute { removeUndone(entry) }
        publish()
        true
    }

    /**
     * Ends the session (Done): waits for every queued shot, clears the staging folder, deletes the document if this session created it and it
     * has no page, and returns the document with the number of pages kept. Shots handed in afterwards are refused. Blocking.
     */
    fun finish(): ScanSessionResult = end(keepPages = true)

    /**
     * Ends the session without keeping it (back without Done). As in OpenScan, where leaving the screen without Done returned no pages, every
     * page this session stored is removed again, and a document it created is deleted; pages the document had before are kept. Blocking.
     */
    fun cancel(): ScanSessionResult = end(keepPages = false)

    private fun end(keepPages: Boolean): ScanSessionResult {
        synchronized(lock) {
            if (!closed) {
                closed = true
                if (!keepPages) entries.forEach { it.undone = true }
                // Runs after every queued shot, each of which removes its own page once undone; this takes the ones stored before.
                if (!keepPages) worker.execute { synchronized(lock) { entries.filter { it.done } }.forEach { removeUndone(it) } }
                worker.shutdown()
            }
        }
        while (!worker.awaitTermination(1, TimeUnit.SECONDS)) Unit
        clearStaging()
        val id = docId
        var doc = id?.let { library.document(it) }
        if (doc != null && created && doc.pages.isEmpty()) {
            library.delete(doc.id)
            doc = null
        }
        docId = doc?.id
        val kept = synchronized(lock) { entries.count { it.pageId != null && !it.undone } }
        val result = ScanSessionResult(doc?.id, if (doc == null) 0 else kept)
        synchronized(lock) { publish(finished = true) }
        return result
    }

    /** Runs on the worker: stores [entry] unless it was undone first, then gets rid of the shot file. */
    private fun process(entry: Entry) {
        val skip = synchronized(lock) { entry.undone }
        var pageId: String? = null
        if (!skip) {
            // Nothing in the pipeline should throw, but a task that did would leave its shot pending for good.
            pageId = try {
                store(entry)
            } catch (e: Exception) {
                null
            }
        }
        entry.shot?.delete()
        synchronized(lock) {
            entry.pageId = pageId
            entry.done = true
            if (!skip && pageId == null) failures += ShotFailure(entry.seq, entry.origin)
            publish()
        }
        // Undone while it was being stored: the page was added all the same, and goes again now.
        if (pageId != null && synchronized(lock) { entry.undone }) removeUndone(entry)
    }

    /** Stores [entry] as the new last page of the session's document, creating that document on the first page; the new page's id or null. */
    private fun store(entry: Entry): String? {
        val id = docId ?: try {
            library.create().id.also {
                docId = it
                created = true
            }
        } catch (e: IOException) {
            return null
        }
        val doc = library.addCapture(id, entry.source, entry.quad, keepOriginal, defaultFilter) ?: return null
        // addCapture appends, and only this worker adds pages for this session, so the new page is the last.
        return doc.pages.lastOrNull()?.id
    }

    /** Runs on the worker: deletes the page of an undone [entry] if it was stored. */
    private fun removeUndone(entry: Entry) {
        val pageId = synchronized(lock) { entry.pageId?.also { entry.pageId = null } } ?: return
        val id = docId ?: return
        try {
            library.deletePage(id, pageId)
        } catch (e: IOException) {
            return // the record could not be written; the page stays in the document
        }
        // Deleting a document's last page deletes the document (ScanLibrary.deletePage); the next page then starts a new one.
        if (created && library.document(id) == null) {
            docId = null
            created = false
        }
        synchronized(lock) { publish() }
    }

    /** Pushes the current state; callers hold [lock]. */
    private fun publish(finished: Boolean = _state.value.finished) {
        _state.value = ScanSessionState(
            documentId = docId,
            pages = entries.filter { !it.undone }.mapNotNull { it.pageId },
            pending = entries.count { !it.undone && !it.done },
            failures = failures.toList(),
            finished = finished,
        )
    }

    companion object {
        /** A session over the app's scan library that clears the app's staging folder when it ends. */
        fun start(context: Context, documentId: String? = null, keepOriginal: Boolean = true, defaultFilter: String? = null): ScanSession {
            val app = context.applicationContext
            return ScanSession(ScanLibrary(ScanFiles.libraryDir(app)), documentId, keepOriginal, defaultFilter, { ScanFiles.clearStaging(app) })
        }
    }
}
