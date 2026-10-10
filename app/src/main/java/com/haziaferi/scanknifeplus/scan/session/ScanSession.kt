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

/** How a session was ended: [ScanSession.finish] keeps its pages, [ScanSession.cancel] (and [ScanSession.close]) takes them back. */
enum class SessionEnd { FINISHED, CANCELLED }

/**
 * How a session ended: the document it filled (null if it holds nothing from a new document), how many pages it kept there, and which ending
 * was applied ([end] is the first ending asked for, whichever call returned this).
 */
data class ScanSessionResult(val documentId: String?, val pagesAdded: Int, val end: SessionEnd)

/**
 * One scan session: the non-visual part of OpenScan's live-scan screen, between the camera and the scan library. Camera shots ([capture]) and
 * gallery picks ([import]) join one queue and are stored, in the order they were handed in, on one background thread through
 * [ScanLibrary.addCapture], each as a new last page of the session's document with the session's [keepOriginal] and [defaultFilter].
 *
 * The session fills an existing document ([documentId] given: pages are added after the ones it has) or a new one, created when the first page
 * is stored, so a session that stores nothing leaves no document behind. Taking pages back never deletes the document: one the session did
 * not create is never deleted at all, even when it is left empty, and one it created stays (with a stable id) until the session ends, which
 * deletes it if it has no page.
 *
 * Differences from OpenScan, each deliberate:
 *  - The shutter never waits for a page to be encoded. OpenScan's `_capturing` gate held the shutter, auto-capture and Done until the last shot
 *    was processed; here [capture] only queues, and the shots are worked off in the background in shutter order.
 *  - Pages go into the document as they are stored, not all at once when the screen is closed, so the document (and [state]) grows while the
 *    user frames the next page, and a page already stored survives the process being killed.
 *  - One attempt per shot: a shot that cannot be stored is reported in [ScanSessionState.failures] right away. OpenScan staged it raw, tried
 *    again when the document adopted it, and then skipped it silently.
 *  - Each session has its own staging folder, [shotDir], deleted when the session ends; OpenScan wiped the whole cache directory.
 *
 * Shot files: the camera writes its shots into [shotDir]. [ScanLibrary.addCapture] only reads its source, so the session deletes each camera
 * shot itself once it is stored, has failed or was undone; picked images are only read. If the process dies, shots still in the queue are
 * lost (their folder is swept by the next [start]); pages stored before that stay in the document.
 *
 * [capture], [import] and [undoLast] return at once and may be called from the main thread; [finish], [cancel] and [close] block until the
 * queue is done. All methods are thread-safe. A session must be ended: [close] (cancel unless already ended) lets `use {}` make sure its
 * thread and folder do not outlive it.
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
    /** One shot in the queue. Fields are guarded by [lock]; [pageId] is set by the worker once the page is stored. */
    private class Entry(val seq: Long, val origin: ShotOrigin, val source: ImageSource, val quad: Quad?, val shot: File?) {
        var pageId: String? = null
        var done = false
        var undone = false
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

    private val failures = mutableListOf<ShotFailure>()
    private val _state = MutableStateFlow(ScanSessionState(documentId = documentId))

    /** The session as it stands; updated from the worker thread. */
    val state: StateFlow<ScanSessionState> = _state.asStateFlow()

    init {
        shotDir.mkdirs()
        synchronized(ACTIVE) { ACTIVE += shotDir.absoluteFile }
    }

    /**
     * Queues camera shot [shot] (a JPEG the camera wrote into [shotDir]) to be stored cropped to [quad] (fractional portrait coordinates, null
     * for the whole image). Returns at once; false if the session has already ended, in which case the shot file is deleted.
     */
    fun capture(shot: File, quad: Quad?): Boolean = enqueue(ShotOrigin.CAMERA, ImageSource.of(shot), quad, shot)

    /** Queues gallery picks [uris], read through [resolver], as whole-image pages (OpenScan's imported pages have no quad). */
    fun import(resolver: ContentResolver, uris: List<Uri>): Boolean = uris.all { import(ImageSource.of(resolver, it)) }

    /** Queues one picked image as a whole-image page; false if the session has already ended. */
    fun import(source: ImageSource): Boolean = enqueue(ShotOrigin.IMPORT, source, null, null)

    private fun enqueue(origin: ShotOrigin, source: ImageSource, quad: Quad?, shot: File?): Boolean {
        synchronized(lock) {
            if (ending != null) {
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
     * soon as it is, and a stored page is deleted from the document in the background (if that delete fails, the page comes back into
     * [state]). Pages the document had before the session are never touched. Returns false if the session has no page to take back.
     */
    fun undoLast(): Boolean = synchronized(lock) {
        if (ending != null) return false
        val entry = entries.lastOrNull { !it.undone && (!it.done || it.pageId != null) } ?: return false
        entry.undone = true
        // A stored page is deleted on the worker, behind anything already queued; a pending one is dealt with by its own task.
        if (entry.done) worker.execute { removeUndone(entry) }
        publish()
        true
    }

    /**
     * Ends the session (Done): waits for every queued shot, deletes [shotDir], deletes the document if this session created it and it has no
     * page, and returns the document with the number of pages kept. Shots handed in afterwards are refused. If the session was already ended,
     * the first ending stands (see [ScanSessionResult.end]). Blocking.
     */
    @WorkerThread
    fun finish(): ScanSessionResult = end(SessionEnd.FINISHED)

    /**
     * Ends the session without keeping it (back without Done). As in OpenScan, where leaving the screen without Done returned no pages, every
     * page this session stored is removed again and a document it created is deleted; a document it did not create, and the pages it had
     * before, are kept. If the session was already ended, the first ending stands (see [ScanSessionResult.end]). Blocking.
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
            val kept = entries.count { it.pageId != null && !it.undone }
            publish(finished = true)
            ScanSessionResult(doc?.id, if (doc == null) 0 else kept, applied)
        }
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
        val undone = synchronized(lock) {
            entry.pageId = pageId
            entry.done = true
            // A shot undone while it was being stored is no failure, whatever became of it.
            if (pageId == null && !entry.undone) failures += ShotFailure(entry.seq, entry.origin)
            publish()
            entry.undone
        }
        // Undone while it was being stored: the page was added all the same, and goes again now.
        if (pageId != null && undone) removeUndone(entry)
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

    /**
     * Runs on the worker: deletes the page of an undone [entry]. The document is always kept (an empty one this session created goes when the
     * session ends). The page is forgotten only once it is verifiably gone; if the delete failed, the entry counts as kept again.
     */
    private fun removeUndone(entry: Entry) {
        val pageId = synchronized(lock) { entry.pageId } ?: return
        val id = docId ?: return
        try {
            library.deletePage(id, pageId, keepDocument = true)
        } catch (e: IOException) {
            // Checked below: the record could not be written, so the page is still there.
        }
        val gone = library.document(id)?.pages?.none { it.id == pageId } ?: true
        synchronized(lock) {
            if (gone) entry.pageId = null else entry.undone = false
            publish()
        }
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
