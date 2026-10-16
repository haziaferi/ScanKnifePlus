package com.haziaferi.scanknifeplus.ui.scan

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.ScanPreferences
import com.haziaferi.scanknifeplus.scan.camera.ScanCameraController
import com.haziaferi.scanknifeplus.scan.camera.ScanCameraState
import com.haziaferi.scanknifeplus.scan.export.SavedPdf
import com.haziaferi.scanknifeplus.scan.export.ScanExport
import com.haziaferi.scanknifeplus.scan.library.ScanDocument
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scan.library.ScanPage
import com.haziaferi.scanknifeplus.scan.live.CaptureRequest
import com.haziaferi.scanknifeplus.scan.live.LiveScanPipeline
import com.haziaferi.scanknifeplus.scan.session.ScanSession
import com.haziaferi.scanknifeplus.scan.session.ScanSessionState
import com.haziaferi.scanknifeplus.scan.session.ShotFailure
import com.haziaferi.scanknifeplus.scanner.cv.BookPage
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How the scanner captures: one page then Review, pages until Done, or an open book as two pages per shot. */
internal enum class CaptureMode { SINGLE, MULTI, BOOK }

/** Which screen of the flow shows. */
internal enum class ScanPhase { SCANNER, REVIEW }

/** A short message the flow shows until the next action; none of them blocks scanning. */
internal enum class ScanNotice { SHOT_FAILED, EDIT_FAILED, SAVE_FAILED }

/** A page deleted in Review that Undo can still bring back: it stays in the library until [ScanFlowModel.commitDelete]. */
internal data class DeletedPage(val page: ScanPage, val number: Int)

/** The left and right page of one Book mode shot, shown as a spread with its split line. */
internal data class Spread(val left: ScanPage, val right: ScanPage, val leftNumber: Int) {
    val split: Double get() = left.book!!.split
}

/**
 * The scan flow's state and actions, independent of any screen: the camera ([controller]) feeding live detection ([pipeline]), one
 * [ScanSession] filling a new document, and the Review edits on that document through [library]. State is Compose state, written on the main
 * thread; library and session work runs on [io]. Survives configuration changes in [ScanFlowHolder]; [close] ends it.
 */
internal class ScanFlowModel(
    context: Context,
    private val library: ScanLibrary = ScanLibrary(ScanFiles.libraryDir(context)),
    private val startSession: () -> ScanSession? = { ScanSession.start(context) },
    private val export: ScanExport = ScanExport(context.applicationContext, library),
    private val prefs: ScanPreferences = ScanPreferences(context),
    main: CoroutineContext = Dispatchers.Main.immediate,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val edits = Mutex()
    private var session: ScanSession? = null
    private var closed = false

    val controller = ScanCameraController(context)

    val pipeline: LiveScanPipeline = LiveScanPipeline(object : LiveScanPipeline.Listener {
        override fun onSmoothedQuadChanged(quad: Quad?) = onMain { this@ScanFlowModel.quad = quad }

        override fun onHoldingSteadyChanged(steady: Boolean) = onMain { holdingSteady = steady }

        override fun onAutoCapture(request: CaptureRequest) = onMain {
            if (phase == ScanPhase.SCANNER && !closed) shoot(request) else pipeline.endCapture()
        }
    })

    var phase by mutableStateOf(ScanPhase.SCANNER)
    var mode by mutableStateOf(CaptureMode.SINGLE)
        private set
    var autoCapture by mutableStateOf(prefs.autoCapture)
        private set
    var camera by mutableStateOf(ScanCameraState())
        private set
    var quad by mutableStateOf<Quad?>(null)
        private set
    var holdingSteady by mutableStateOf(false)
        private set
    var sessionState by mutableStateOf(ScanSessionState())
        private set
    var document by mutableStateOf<ScanDocument?>(null)
        private set
    var selectedId by mutableStateOf<String?>(null)
    var deleted by mutableStateOf<DeletedPage?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<ScanNotice?>(null)

    /** The clockwise quarter turns that bring the device's current hold upright; set by the screen, read at the shutter in Book mode. */
    var deviceQuarterTurns by mutableIntStateOf(0)

    init {
        controller.frameListener = pipeline::onFrame
        controller.stateListener = { s ->
            camera = s
            pipeline.setZooming(s.zooming)
        }
        pipeline.setAutoCaptureEnabled(autoCapture)
        scope.launch {
            val started = withContext(io) { startSession() }
            if (started == null || closed) {
                started?.let { withContext(io) { it.cancel() } }
                return@launch
            }
            session = started
            started.state.collect { onSessionState(it) }
        }
    }

    /**
     * The session's pages in document order, without one deleted in Review and awaiting Undo. A shot taken back (a retake) leaves at once,
     * before the session has removed its page from the library.
     */
    val pages: List<ScanPage> get() {
        val kept = sessionState.pages.toSet()
        return document?.pages.orEmpty().filter { it.id in kept && it.id != deleted?.page?.id }
    }

    /** The page Review shows large: the selected one, else the last. */
    val selected: ScanPage? get() = pages.firstOrNull { it.id == selectedId } ?: pages.lastOrNull()

    /** The spread [selected] belongs to, if it is one page of a Book mode shot whose other page is beside it with its own original. */
    val spread: Spread? get() = selected?.let { spreadOf(it) }

    /** Whether the session holds or is storing anything that leaving would throw away. */
    val hasPages: Boolean get() = sessionState.count > 0 || pages.isNotEmpty()

    /** Whether Done can save a PDF: every shot is stored and at least one page is left. */
    val canSave: Boolean get() = sessionState.pending == 0 && pages.isNotEmpty() && !busy

    /** The capture mode can change only before the first shot, so one session never mixes Review layouts. */
    val canChangeMode: Boolean get() = sessionState.count == 0

    val failure: ShotFailure? get() = sessionState.failures.firstOrNull()

    /** The file name the save picker suggests: the document's name, made safe (`scanknife-scan-YYYY-MM-DD-HHmmss.pdf` unless renamed). */
    val pdfName: String? get() = document?.let(export::fileName)

    /** File [name] of the document, e.g. a page's image or original. */
    fun fileOf(name: String): File? = document?.id?.let { library.file(it, name) }

    fun chooseMode(next: CaptureMode) {
        if (canChangeMode) mode = next
    }

    /** Turns auto-capture on or off and remembers the choice for the next scan (decision 8a). */
    fun toggleAutoCapture() {
        autoCapture = !autoCapture
        prefs.autoCapture = autoCapture
        pipeline.setAutoCaptureEnabled(autoCapture)
    }

    /** The manual shutter; ignored while a capture is under way. */
    fun shutter() {
        val request = pipeline.beginCapture() ?: return
        shoot(request)
    }

    private fun shoot(request: CaptureRequest) {
        scope.launch {
            try {
                val dir = session?.shotDir ?: return@launch
                val shot = controller.takePicture(dir)
                if (shot == null) {
                    notice = ScanNotice.SHOT_FAILED
                    return@launch
                }
                pipeline.notifyCaptured()
                add(shot, request.quad, deviceQuarterTurns)
            } finally {
                pipeline.endCapture()
            }
        }
    }

    /**
     * Queues camera shot [shot] cropped to [quad] in the current mode: a Book mode shot becomes two pages, turned upright by [quarterTurns]. A
     * Single page shot opens Review. False if the session is not running.
     */
    fun add(shot: File, quad: Quad?, quarterTurns: Int): Boolean {
        val s = session ?: return false
        if (notice == ScanNotice.SHOT_FAILED) notice = null
        val queued = if (mode == CaptureMode.BOOK) s.captureBook(shot, quad, quarterTurns = quarterTurns) else s.capture(shot, quad)
        if (queued && mode == CaptureMode.SINGLE) phase = ScanPhase.REVIEW
        return queued
    }

    fun retry(failure: ShotFailure) {
        session?.retry(failure)
    }

    fun dismiss(failure: ShotFailure) {
        session?.dismiss(failure)
    }

    /** Opens Review (Done on the Scanner). */
    fun review() {
        if (sessionState.count > 0) phase = ScanPhase.REVIEW
    }

    /**
     * Back from Review to the Scanner: Add page in Multi page and Book, a retake in Single page, whose one page is taken back so the next shot
     * replaces it.
     */
    fun backToScanner() {
        if (mode == CaptureMode.SINGLE) {
            deleted = null
            session?.undoLast()
        } else {
            commitDelete()
        }
        notice = null
        phase = ScanPhase.SCANNER
    }

    fun select(pageId: String) {
        commitDelete()
        selectedId = pageId
    }

    /** Turns the selected page a quarter turn clockwise; the turn is recorded against its kept original. */
    fun rotate() = edit { id, page -> library.rotatePage(id, page.id, 1) }

    /** Re-crops the selected page from its kept original to [quad] (fractions of the original's upright size). */
    fun crop(quad: Quad) = edit { id, page -> library.recropPage(id, page.id, quad) }

    /** Applies filter [name] (a DocumentFilters name) to the selected page. */
    fun applyFilter(name: String) = edit { id, page -> library.applyFilter(id, page.id, name) }

    /** Moves the selected page [delta] places (-1 earlier, +1 later) among the pages. */
    fun move(delta: Int) {
        val all = document?.pages ?: return
        edit { id, page ->
            val from = all.indexOfFirst { it.id == page.id }
            val to = from + delta
            if (from < 0 || to !in all.indices) null else library.movePage(id, from, to)
        }
    }

    /** Moves the split line of the selected spread to [split] and re-cuts both pages from their own originals. */
    fun resplit(split: Double) {
        val s = spread ?: return
        edit { id, _ -> library.resplitBook(id, s.left.id, s.right.id, split) }
    }

    /** Hides the selected page with Undo; the page leaves the library at the next action ([commitDelete]). */
    fun delete() {
        commitDelete()
        val page = selected ?: return
        val visible = pages
        val index = visible.indexOf(page)
        deleted = DeletedPage(page, index + 1)
        selectedId = (visible.getOrNull(index + 1) ?: visible.getOrNull(index - 1))?.id
    }

    fun undoDelete() {
        val d = deleted ?: return
        deleted = null
        selectedId = d.page.id
    }

    /** Deletes the page awaiting Undo for good; the document itself is kept even when emptied, as the session ends it. */
    fun commitDelete() {
        val d = deleted ?: return
        deleted = null
        val id = document?.id ?: return
        scope.launch {
            edits.withLock {
                val doc = withContext(io) { library.deletePage(id, d.page.id, keepDocument = true) }
                if (doc != null) document = doc
            }
        }
    }

    /**
     * Writes the document as a PDF into [dest] (a document the system picker created) with page [size] and, unless empty, [password], then ends
     * the session keeping its pages, and hands the result to [onSaved]. A failure leaves Review as it was, with [ScanNotice.SAVE_FAILED].
     */
    fun save(dest: Uri, size: PdfPageSize, password: String, onSaved: (SavedPdf) -> Unit) {
        commitDelete()
        busy = true
        scope.launch {
            val saved = edits.withLock {
                val id = document?.id
                if (id == null) null else withContext(io) { export.save(id, dest, size = size, password = password.ifEmpty { null }) }
            }
            if (saved == null) {
                busy = false
                notice = ScanNotice.SAVE_FAILED
                return@launch
            }
            end(keep = true)
            busy = false
            onSaved(saved)
        }
    }

    /** Leaves the flow without saving a PDF (decision 6): [keep] keeps the pages in the scan library, otherwise they are discarded. */
    fun leave(keep: Boolean, onDone: () -> Unit) {
        if (keep) commitDelete() else deleted = null
        busy = true
        scope.launch {
            end(keep)
            onDone()
        }
    }

    /** Ends the session ([ScanSession.finish] or [ScanSession.cancel]) once pending edits are written, and releases the camera. */
    private suspend fun end(keep: Boolean) {
        val s = session
        edits.withLock {
            if (s != null) withContext(io) { if (keep) s.finish() else s.cancel() }
        }
        close()
    }

    /** Releases the camera and detection and stops the flow's work; a session still running is left to [abandon] or [end]. */
    fun close() {
        if (closed) return
        closed = true
        mainHandler.removeCallbacksAndMessages(null)
        controller.release()
        pipeline.dispose()
        scope.cancel()
    }

    /** For a flow dropped without leaving it (the activity finished): keeps what was scanned, on a background thread, and closes. */
    fun abandon() {
        val s = session
        close()
        if (s != null) Thread({ s.finish() }, "scan-abandon").start()
    }

    private fun edit(change: (String, ScanPage) -> ScanDocument?) {
        commitDelete()
        val id = document?.id ?: return
        val page = selected ?: return
        busy = true
        scope.launch {
            edits.withLock {
                val doc = withContext(io) {
                    try {
                        change(id, page)
                    } catch (e: Exception) {
                        Log.w(TAG, "Page edit failed", e)
                        null
                    }
                }
                if (doc != null) document = doc else notice = ScanNotice.EDIT_FAILED
            }
            busy = false
        }
    }

    private suspend fun onSessionState(state: ScanSessionState) {
        val before = sessionState.pages
        sessionState = state
        val id = state.documentId ?: return
        val doc = edits.withLock { withContext(io) { library.document(id) } } ?: return
        document = doc
        // A new shot is what the user wants to see first.
        state.pages.lastOrNull { it !in before }?.let { selectedId = it }
    }

    private fun spreadOf(page: ScanPage): Spread? {
        val all = pages
        val i = all.indexOf(page)
        val book = page.book ?: return null
        val (left, right) = when (book.side) {
            BookPage.LEFT -> page to all.getOrNull(i + 1)
            BookPage.RIGHT -> all.getOrNull(i - 1) to page
        }
        if (left == null || right == null) return null
        val l = left.book ?: return null
        val r = right.book ?: return null
        if (l.side != BookPage.LEFT || r.side != BookPage.RIGHT || l.shot != r.shot || l.spread != r.spread) return null
        if (left.original == null || right.original == null) return null
        return Spread(left, right, all.indexOf(left) + 1)
    }

    private fun onMain(action: () -> Unit) {
        mainHandler.post { if (!closed) action() }
    }

    private companion object {
        const val TAG = "ScanFlow"
    }
}

/** Keeps the flow's [model] across configuration changes; a flow the activity drops without leaving it keeps its pages ([ScanFlowModel.abandon]). */
internal class ScanFlowHolder : ViewModel() {
    var model: ScanFlowModel? = null

    override fun onCleared() {
        model?.abandon()
        model = null
    }
}
