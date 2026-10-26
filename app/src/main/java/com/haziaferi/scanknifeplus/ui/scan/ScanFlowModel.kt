package com.haziaferi.scanknifeplus.ui.scan

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.haziaferi.scanknifeplus.ocr.OcrEngine
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.ocr.TesseractOcrEngine
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.ScanPreferences
import com.haziaferi.scanknifeplus.scan.camera.ScanCameraController
import com.haziaferi.scanknifeplus.scan.camera.ScanCameraState
import com.haziaferi.scanknifeplus.scan.export.OcrCache
import com.haziaferi.scanknifeplus.scan.export.OcrSettings
import com.haziaferi.scanknifeplus.scan.export.SavedPdf
import com.haziaferi.scanknifeplus.scan.export.ScanExport
import com.haziaferi.scanknifeplus.scan.export.ScanPdfExporter
import com.haziaferi.scanknifeplus.scan.export.ScanText
import com.haziaferi.scanknifeplus.scan.export.ScanTextReader
import com.haziaferi.scanknifeplus.scan.library.ScanDocument
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scan.library.ScanPage
import com.haziaferi.scanknifeplus.scan.live.CaptureRequest
import com.haziaferi.scanknifeplus.scan.live.LiveScanPipeline
import com.haziaferi.scanknifeplus.scan.session.ScanSession
import com.haziaferi.scanknifeplus.scan.session.ScanSessionState
import com.haziaferi.scanknifeplus.scan.session.ShotFailure
import com.haziaferi.scanknifeplus.scanner.cv.BookPage
import com.haziaferi.scanknifeplus.scanner.cv.BookSplit
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.haziaferi.scanknifeplus.storage.ScanFolder
import com.haziaferi.scanknifeplus.storage.WriteResult
import com.haziaferi.scanknifeplus.ui.folder.pdfMaker
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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

/** What the Save dialog chose: page [size], a [password] (empty for none) and, when Make searchable is on, how to read the [text]. */
internal data class SaveOptions(val size: PdfPageSize, val password: String, val text: TextOptions? = null)

/** Make searchable's choices: the OCR [language] and [reading], and whether to also save the text as a .txt copy ([txt]). */
internal data class TextOptions(val language: OcrLanguage, val reading: OcrReading, val txt: Boolean)

/** A save reading its pages: page [page] of [total] is being read. */
internal data class ReadingProgress(val page: Int, val total: Int)

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
    private val exporter: ScanPdfExporter = ScanPdfExporter(library, ScanFiles.exportDir(context)),
    private val export: ScanExport = ScanExport(context.applicationContext, library, exporter),
    private val prefs: ScanPreferences = ScanPreferences(context),
    main: CoroutineContext = Dispatchers.Main.immediate,
    private val io: CoroutineContext = Dispatchers.IO,
    takePicture: (suspend (File) -> File?)? = null,
    private val beforeSession: suspend () -> Unit = {},
    private val openEngine: () -> OcrEngine = { TesseractOcrEngine.open(context.applicationContext) },
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val edits = Mutex()

    // Guards session and closed between close() on the main thread and the session starting on io.
    private val sessionLock = Any()
    private var session: ScanSession? = null
    private var closed = false

    val controller = ScanCameraController(context)
    private val takePicture: suspend (File) -> File? = takePicture ?: controller::takePicture

    val pipeline: LiveScanPipeline = LiveScanPipeline(object : LiveScanPipeline.Listener {
        override fun onSmoothedQuadChanged(quad: Quad?) = onMain { this@ScanFlowModel.quad = quad }

        override fun onHoldingSteadyChanged(steady: Boolean) = onMain { holdingSteady = steady }

        override fun onLowLightChanged(lowLight: Boolean) = onMain { this@ScanFlowModel.lowLight = lowLight }

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

    /** Whether the scene is too dark for reliable detection (scanner-core's LowLightDetector, through the pipeline). */
    var lowLight by mutableStateOf(false)
        private set

    // Picked images handed in before the session finished starting; imported as soon as it has.
    private var pendingImport: Pair<ContentResolver, List<Uri>>? = null
    var sessionState by mutableStateOf(ScanSessionState())
        private set
    var document by mutableStateOf<ScanDocument?>(null)
        private set
    var selectedId by mutableStateOf<String?>(null)
    var deleted by mutableStateOf<DeletedPage?>(null)
        private set

    /** Pages whose delete is committed but not yet written; they stay hidden until the library has removed them. */
    private var deleting by mutableStateOf(emptySet<String>())

    /** Whether a shot is between the shutter and the session's queue. */
    private var shooting by mutableStateOf(false)

    /** Whether the Single page has been rotated, cropped or filtered, so a retake would throw that work away. */
    var edited by mutableStateOf(false)
        private set

    /** The save options chosen before the system file picker opened; kept here so they outlive a recreated activity. Never written to disk. */
    var pendingSave: SaveOptions? = null
    var busy by mutableStateOf(false)
        private set

    /** Which page a searchable save is reading, while it reads; null otherwise. */
    var reading by mutableStateOf<ReadingProgress?>(null)
        private set

    /** The numbers of the pages the last save found looking handwritten, for the notice after it. */
    var handwritten: List<Int> = emptyList()
        private set

    /** A saved PDF's .txt copy awaiting its own picker, as (name to suggest, text); the flow stays open until [saveText] or [skipText]. */
    var textToSave: Pair<String, String>? = null
        private set

    // False while the app is in the background: a save's OCR then stops after the page it is reading.
    private val foreground = MutableStateFlow(true)
    private var reader: ScanTextReader? = null
    private var withoutText = false

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
            // The app's launch sweep comes first: it must finish before any session starts.
            beforeSession()
            // Registered on io under the lock, so a session that finishes starting after close() is still ended, as ScanSession requires.
            val started = withContext(io) {
                val s = startSession() ?: return@withContext null
                val kept = synchronized(sessionLock) { if (closed) false else true.also { session = s } }
                if (kept) s else null.also { s.cancel() }
            } ?: return@launch
            pendingImport?.let { (resolver, uris) -> started.import(resolver, uris) }
            pendingImport = null
            started.state.collect { onSessionState(it) }
        }
    }

    /**
     * The session's pages in document order, without one deleted in Review and awaiting Undo. A shot taken back (a retake) leaves at once,
     * before the session has removed its page from the library.
     */
    val pages: List<ScanPage> get() {
        val kept = sessionState.pages.toSet()
        val hidden = deleting + listOfNotNull(deleted?.page?.id)
        return document?.pages.orEmpty().filter { it.id in kept && it.id !in hidden }
    }

    /** The page Review shows large: the selected one, else the last. */
    val selected: ScanPage? get() = pages.firstOrNull { it.id == selectedId } ?: pages.lastOrNull()

    /** The spread [selected] belongs to, if it is one page of a Book mode shot whose other page is beside it with its own original. */
    val spread: Spread? get() = selected?.let { spreadOf(it) }

    /** Whether the session holds or is storing anything that leaving would throw away. */
    val hasPages: Boolean get() = sessionState.count > 0 || pages.isNotEmpty()

    /** Whether Done can save a PDF: every shot is stored and at least one page is left. */
    val canSave: Boolean get() = sessionState.pending == 0 && pages.isNotEmpty() && !busy

    /** The pages the Scanner counts: the visible ones plus those still being stored. */
    val count: Int get() = pages.size + sessionState.pending

    /**
     * The capture mode can change only before the first shot, so one session never mixes Review layouts; a shot on its way or a failed
     * one waiting for a retry counts.
     */
    val canChangeMode: Boolean get() = sessionState.count == 0 && sessionState.failures.isEmpty() && !shooting

    /** Single page takes one shot at a time: the next waits until that page is retaken or its failure dismissed. */
    val canShoot: Boolean
        get() = !shooting && (mode != CaptureMode.SINGLE || (sessionState.count == 0 && sessionState.failures.isEmpty()))

    /** Back from Single page Review would retake an edited page, so it asks first (decision 6). */
    val backNeedsPrompt: Boolean get() = mode == CaptureMode.SINGLE && edited && pages.isNotEmpty()

    val failure: ShotFailure? get() = sessionState.failures.firstOrNull()

    /**
     * Where Crop starts for the selected page: its recorded quad, else for a Book mode page its half of the spread, else (null) the whole photo.
     * In fractions of the page's original, which is what Crop shows.
     */
    val cropStart: Quad? get() = selected?.let { p -> p.quad ?: p.book?.let { BookSplit.page(it.spread, it.side, it.split, it.turns) } }

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
        if (!canShoot) return
        val request = pipeline.beginCapture() ?: return
        shoot(request)
    }

    private fun shoot(request: CaptureRequest) {
        if (!canShoot) {
            pipeline.endCapture()
            return
        }
        // The mode and the hold as the shutter fires, not as they are once the still is written.
        val shotMode = mode
        val turns = deviceQuarterTurns
        shooting = true
        scope.launch {
            try {
                val dir = synchronized(sessionLock) { session }?.shotDir ?: return@launch
                val shot = takePicture(dir)
                if (shot == null) {
                    notice = ScanNotice.SHOT_FAILED
                    return@launch
                }
                pipeline.notifyCaptured()
                add(shot, request.quad, turns, shotMode)
            } finally {
                shooting = false
                pipeline.endCapture()
            }
        }
    }

    /**
     * Queues camera shot [shot] cropped to [quad] as a [shotMode] shot: a Book mode shot becomes two pages, turned upright by [quarterTurns].
     * A Single page shot opens Review once its page is stored; one that fails stays on the Scanner with retry and dismiss (7b). False if the
     * session is not running.
     */
    fun add(shot: File, quad: Quad?, quarterTurns: Int, shotMode: CaptureMode = mode): Boolean {
        val s = synchronized(sessionLock) { session } ?: return false
        if (notice == ScanNotice.SHOT_FAILED) notice = null
        return if (shotMode == CaptureMode.BOOK) s.captureBook(shot, quad, quarterTurns = quarterTurns) else s.capture(shot, quad)
    }

    /**
     * Starts the flow from picked images [uris] (read through [resolver]) instead of the camera: each becomes a whole-image page, as OpenScan
     * imports them, and the flow opens Review as a Multi page scan.
     */
    fun importImages(resolver: ContentResolver, uris: List<Uri>) {
        if (uris.isEmpty()) return
        mode = CaptureMode.MULTI
        phase = ScanPhase.REVIEW
        val s = synchronized(sessionLock) { session }
        if (s == null) pendingImport = resolver to uris else s.import(resolver, uris)
    }

    fun retry(failure: ShotFailure) {
        synchronized(sessionLock) { session }?.retry(failure)
    }

    fun dismiss(failure: ShotFailure) {
        synchronized(sessionLock) { session }?.dismiss(failure)
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
            edited = false
            synchronized(sessionLock) { session }?.undoLast()
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
        // Indices come from the record as it is when the move runs, after any committed delete has been written.
        edit { id, page ->
            val all = library.document(id)?.pages ?: return@edit null
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
        deleting = deleting + d.page.id
        scope.launch {
            try {
                edits.withLock {
                    val doc = withContext(io) { library.deletePage(id, d.page.id, keepDocument = true) }
                    if (doc != null) document = doc
                }
            } finally {
                deleting = deleting - d.page.id
            }
        }
    }

    /**
     * Writes the document as a PDF into [dest] (a document the system picker created) with the [options]' page size and password (unless empty),
     * searchable when they say so, then ends the session keeping its pages, and hands the result to [onSaved]. A .txt copy asked for waits in
     * [textToSave], and the flow stays open until it is saved or skipped. A failure leaves Review as it was, with [ScanNotice.SAVE_FAILED].
     */
    fun save(dest: Uri, options: SaveOptions, onSaved: (SavedPdf) -> Unit) {
        pendingSave = null
        commitDelete()
        busy = true
        scope.launch {
            val outcome = edits.withLock {
                val doc = document ?: return@withLock null
                val text = readText(doc.id, options)
                withContext(io) {
                    val password = options.password.ifEmpty { null }
                    val pdf = export.save(doc.id, dest, size = options.size, password = password, text = text?.read.orEmpty())
                    val txt = text?.takeIf { options.text?.txt == true }?.let { export.textFileName(dest, doc) to it.txt() }
                    pdf?.let { PickerSave(it, text, txt) }
                }
            }
            if (outcome == null) {
                busy = false
                notice = ScanNotice.SAVE_FAILED
                return@launch
            }
            textToSave = outcome.txt
            afterSave(outcome.text)
            end(keep = true, close = outcome.txt == null)
            busy = false
            onSaved(outcome.pdf)
        }
    }

    /** Writes the waiting .txt copy into [dest], the document its file picker created, then closes the flow and calls [onDone]. */
    fun saveText(dest: Uri, onDone: () -> Unit) {
        val (_, text) = textToSave ?: return
        textToSave = null
        scope.launch {
            withContext(io) { export.saveText(dest, text) }
            close()
            onDone()
        }
    }

    /** Drops the waiting .txt copy (its picker was cancelled) and closes the flow. */
    fun skipText() {
        textToSave = null
        close()
    }

    /**
     * Writes the document back into the user's [folder] ([ScanFolder.writeBack]: its PDF at the top level with the [options]' page size and
     * password (unless empty), searchable and with a .txt beside it when they say so, its sources under `Scan sources/`), then ends the session
     * keeping its pages, which the launch sweep removes once the folder holds them. [onDone] gets the result; on anything but
     * [WriteResult.Written] Review stays as it was, with [ScanNotice.SAVE_FAILED] unless the folder could not be reached, which the caller handles.
     */
    fun saveToFolder(folder: ScanFolder, options: SaveOptions, onDone: (WriteResult) -> Unit) {
        pendingSave = null
        commitDelete()
        busy = true
        scope.launch {
            val (result, text) = edits.withLock {
                val id = document?.id ?: return@withLock WriteResult.Failed to null
                val text = readText(id, options)
                val pdf = exporter.pdfMaker(options.size, options.password.ifEmpty { null }, text?.read.orEmpty())
                val txt = text?.takeIf { options.text?.txt == true }?.let { t -> { doc: ScanDocument -> t.txt(doc.pages) } }
                withContext(io) { folder.writeBack(id, pdf, txt) } to text
            }
            if (result is WriteResult.Written) {
                afterSave(text)
                end(keep = true)
            } else if (result != WriteResult.FolderUnavailable) {
                notice = ScanNotice.SAVE_FAILED
            }
            busy = false
            onDone(result)
        }
    }

    /** Pauses a save's OCR after the page it is reading, as the app goes to the background (decision of 2026-10-20, item 6). */
    fun pauseReading() {
        foreground.value = false
    }

    /** Resumes a save's OCR paused by [pauseReading], as the app comes back. */
    fun resumeReading() {
        foreground.value = true
    }

    /** Save without text: stops a save's OCR (the page being read is cancelled) and saves the PDF without a text layer or .txt copy. */
    fun saveWithoutText() {
        withoutText = true
        reader?.stop()
    }

    /**
     * Reads the pages of document [id] for a searchable save, showing the progress in [reading]: null when [options] are not searchable or
     * [saveWithoutText] stopped it. Pages read earlier with the same settings come from the cache.
     */
    private suspend fun readText(id: String, options: SaveOptions): ScanText? {
        val text = options.text ?: return null
        withoutText = false
        val pages = withContext(io) { library.document(id) }?.pages ?: return null
        val settings = OcrSettings(text.language, text.reading, OcrSettings.longEdgeMm(options.size))
        val r = ScanTextReader(library, openEngine, io, foreground)
        reader = r
        return try {
            val read = if (withoutText) null else r.read(id, pages, settings) { page, total -> reading = ReadingProgress(page, total) }
            read?.let { ScanText(pages, it) }
        } finally {
            reader = null
            reading = null
        }
    }

    /** After a save that succeeded: notes its handwritten-looking pages and drops the document's cached reads. */
    private suspend fun afterSave(text: ScanText?) {
        handwritten = text?.handwritten.orEmpty()
        val id = document?.id ?: return
        withContext(io) { OcrCache(library, id).clear() }
    }

    /** Deletes [dest], a document the save picker created for options that were lost (the app was killed meanwhile), so no empty PDF stays. */
    fun dropTarget(dest: Uri) {
        scope.launch {
            withContext(io) {
                try {
                    if (DocumentsContract.isDocumentUri(appContext, dest)) DocumentsContract.deleteDocument(appContext.contentResolver, dest)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not remove the unused $dest", e)
                }
            }
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

    /** Ends the session ([ScanSession.finish] or [ScanSession.cancel]) once pending edits are written, then, unless not to [close], closes. */
    private suspend fun end(keep: Boolean, close: Boolean = true) {
        val s = synchronized(sessionLock) { session }
        edits.withLock {
            if (s != null) withContext(io) { if (keep) s.finish() else s.cancel() }
        }
        if (close) close()
    }

    /** Releases the camera and detection and stops the flow's work; a session still running is left to [abandon] or [end]. */
    fun close() {
        synchronized(sessionLock) {
            if (closed) return
            closed = true
        }
        mainHandler.removeCallbacksAndMessages(null)
        reader?.stop()
        controller.release()
        pipeline.dispose()
        scope.cancel()
    }

    /** For a flow dropped without leaving it (the activity finished): keeps what was scanned, on a background thread, and closes. */
    fun abandon() {
        val s = synchronized(sessionLock) { session }
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
                if (doc != null) {
                    document = doc
                    edited = true
                } else {
                    notice = ScanNotice.EDIT_FAILED
                }
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
        // A new shot is what the user wants to see first; a Single page shot opens Review once it is stored.
        state.pages.lastOrNull { it !in before }?.let {
            selectedId = it
            if (mode == CaptureMode.SINGLE) phase = ScanPhase.REVIEW
        }
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

/** A PDF saved through the picker, with the [text] it was made searchable with and the .txt copy (file name and text) still to save. */
private class PickerSave(val pdf: SavedPdf, val text: ScanText?, val txt: Pair<String, String>?)

/** Keeps the flow's [model] across configuration changes; a flow the activity drops without leaving it keeps its pages ([ScanFlowModel.abandon]). */
internal class ScanFlowHolder : ViewModel() {
    var model: ScanFlowModel? = null

    /** The activity's requested orientation before the flow held it in portrait; null while the flow does not show. */
    var orientationBefore: Int? = null

    override fun onCleared() {
        model?.abandon()
        model = null
    }
}
