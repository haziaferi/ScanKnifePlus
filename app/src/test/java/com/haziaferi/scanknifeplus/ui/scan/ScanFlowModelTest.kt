package com.haziaferi.scanknifeplus.ui.scan

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.scan.ScanPreferences
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.capture.StoredBook
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scan.export.FakeOcrEngine
import com.haziaferi.scanknifeplus.scan.export.PdfAssembler
import com.haziaferi.scanknifeplus.scan.export.SavedPdf
import com.haziaferi.scanknifeplus.scan.export.ScanExport
import com.haziaferi.scanknifeplus.scan.export.ScanPdfExporter
import com.haziaferi.scanknifeplus.scan.library.BookCaptureWriter
import com.haziaferi.scanknifeplus.scan.library.CaptureWriter
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scan.session.ScanSession
import com.haziaferi.scanknifeplus.scanner.cv.BookPage
import com.haziaferi.scanknifeplus.scanner.cv.BookSplit
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.haziaferi.scanknifeplus.scanner.filter.Filter
import com.haziaferi.scanknifeplus.storage.FileFolderStore
import com.haziaferi.scanknifeplus.storage.ScanFolder
import com.haziaferi.scanknifeplus.storage.WorkingCopies
import com.haziaferi.scanknifeplus.storage.WriteResult
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The scan flow's logic over a real session and library with fake image codecs: a shot's text becomes its page's text, a book shot's pages get
 * " left" and " right", and every edit writes a marker into the new file, so each test can read back which page is where. A shot whose text
 * contains "bad" cannot be stored, one with "flaky" cannot be stored the first time only. Library work runs on [io], which a test can hold.
 */
@RunWith(RobolectricTestRunner::class)
class ScanFlowModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root: File = Files.createTempDirectory("flow").toFile()
    private val turnsSeen = Collections.synchronizedList(mutableListOf<Int>())
    private val written = Collections.synchronizedList(mutableListOf<String?>())
    private var now = 1_791_590_400_000L

    private val flaked = Collections.synchronizedSet(mutableSetOf<String>())
    private val io = HeldDispatcher()

    private val writer = CaptureWriter { source, _, page, original ->
        val text = source.open().use { it.readBytes().decodeToString() }
        if ("bad" in text || ("flaky" in text && flaked.add(text))) return@CaptureWriter null
        page.writeText(text)
        original?.writeText("original $text")
        StoredCapture(page, original, cropped = true, pageWidth = 1, pageHeight = 1)
    }
    private val bookWriter = BookCaptureWriter { source, spread, _, quarterTurns, pages, originals ->
        turnsSeen += quarterTurns
        val text = source.open().use { it.readBytes().decodeToString() }
        val stored = pages.toList().zip(listOf("left", "right")).zip(originals!!.toList()) { (page, side), original ->
            page.writeText("$text $side")
            original.writeText("original $text")
            StoredCapture(page, original, cropped = true, pageWidth = 1, pageHeight = 1)
        }
        StoredBook(stored, spread ?: FULL)
    }
    private val images = object : PageImages {
        override fun filter(source: File, filter: Filter, dest: File) = true.also { dest.writeText("${filter.name}(${source.readText()})") }
        override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File) = true.also { dest.writeText("crop(${source.readText()})") }
        override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int) = false
        override fun rotate(source: File, quarterTurns: Int, dest: File) = true.also { dest.writeText("turn$quarterTurns(${source.readText()})") }
    }
    private val library = ScanLibrary(root.resolve("library"), clock = { now++ }, capture = writer, images = images, bookCapture = bookWriter)
    private var sessions = 0
    @Volatile private var assemblyFails = false
    private val texts = Collections.synchronizedList(mutableListOf<List<String?>>())
    private val engine = FakeOcrEngine()
    private val assembler = PdfAssembler { pages, _, _, dest, password, text ->
        written += password
        texts += pages.indices.map { text.getOrNull(it)?.text() }
        dest.writeText(pages.joinToString("|") { it.readText() })
        !assemblyFails
    }
    private val saved = mutableListOf<SavedPdf>()
    private val exporter = ScanPdfExporter(library, root.resolve("export"), images = images, assembler = assembler)
    private val export = ScanExport(context, library, exporter, onSaved = { saved += it })
    private val models = mutableListOf<ScanFlowModel>()

    private fun model(
        io: CoroutineContext = this.io,
        startSession: () -> ScanSession? = { ScanSession(library, root.resolve("staging/s${sessions++}")) },
        takePicture: (suspend (File) -> File?)? = null,
    ): ScanFlowModel = ScanFlowModel(
        context,
        library = library,
        startSession = startSession,
        exporter = exporter,
        export = export,
        main = Dispatchers.Unconfined,
        io = io,
        takePicture = takePicture,
        openEngine = { engine },
    ).also { models += it }

    /** Runs its work at once, like Unconfined, except while [held]; [release] then runs what waited, in order. */
    private class HeldDispatcher : CoroutineDispatcher() {
        @Volatile var held = false
        private val queue = ConcurrentLinkedQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (held) queue += block else block.run()
        }

        fun release() {
            held = false
            while (true) (queue.poll() ?: break).run()
        }
    }

    @After
    fun cleanUp() {
        models.forEach { it.close() }
        root.deleteRecursively()
        ScanPreferences(context).autoCapture = true
    }

    private fun ScanFlowModel.shoot(text: String, turns: Int = 0): Boolean {
        val dir = root.resolve("shots").apply { mkdirs() }
        return add(File(dir, "shot${now++}.jpg").apply { writeText(text) }, QUAD, turns)
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    private fun ScanFlowModel.texts(): List<String> = pages.map { fileOf(it.image)!!.readText() }

    private fun ScanFlowModel.storedTexts(): List<String> {
        val id = document!!.id
        return library.document(id)!!.pages.map { library.file(id, it.image).readText() }
    }

    @Test
    fun `a single page shot opens Review of that page, and going back takes it back for a retake`() {
        val m = model()
        assertTrue(m.canChangeMode)
        assertTrue(m.shoot("one"))
        waitUntil("the page in Review") { m.phase == ScanPhase.REVIEW && m.pages.size == 1 }
        assertEquals("one", m.fileOf(m.selected!!.image)!!.readText())
        assertFalse("the mode is fixed once a shot is in", m.canChangeMode)
        m.chooseMode(CaptureMode.BOOK)
        assertEquals(CaptureMode.SINGLE, m.mode)

        m.backToScanner()
        assertEquals(ScanPhase.SCANNER, m.phase)
        waitUntil("the retake") { m.pages.isEmpty() && library.document(m.document!!.id)!!.pages.isEmpty() }
        assertTrue(m.canChangeMode)
    }

    @Test
    fun `multi page keeps capturing, the newest page is selected, and the arrows move the selected page with its label`() {
        val m = model()
        m.chooseMode(CaptureMode.MULTI)
        m.shoot("a")
        m.shoot("b")
        m.shoot("c")
        assertEquals(ScanPhase.SCANNER, m.phase)
        waitUntil("three pages") { m.pages.size == 3 && m.selected?.let { m.fileOf(it.image)!!.readText() } == "c" }
        m.review()
        assertEquals(ScanPhase.REVIEW, m.phase)

        m.select(m.pages[0].id)
        m.move(1)
        assertEquals(listOf("b", "a", "c"), m.texts())
        assertEquals(listOf("b", "a", "c"), m.storedTexts())
        assertEquals("a", m.fileOf(m.selected!!.image)!!.readText())
        m.move(-1)
        assertEquals(listOf("a", "b", "c"), m.storedTexts())
    }

    @Test
    fun `delete hides the page until the next action, Undo brings it back, and the next action deletes it for good`() {
        val m = model()
        m.chooseMode(CaptureMode.MULTI)
        m.shoot("a")
        m.shoot("b")
        waitUntil("two pages") { m.pages.size == 2 }
        m.select(m.pages[0].id)

        m.delete()
        assertEquals(listOf("b"), m.texts())
        assertEquals(1, m.deleted!!.number)
        assertEquals("the page waits for Undo in the library", listOf("a", "b"), m.storedTexts())
        m.undoDelete()
        assertNull(m.deleted)
        assertEquals(listOf("a", "b"), m.texts())
        assertEquals("a", m.fileOf(m.selected!!.image)!!.readText())

        m.delete()
        m.rotate()
        assertNull(m.deleted)
        assertEquals(listOf("turn1(b)"), m.storedTexts())
    }

    @Test
    fun `rotate, filter and crop edit the selected page, the crop from its kept original`() {
        val m = model()
        m.shoot("page")
        waitUntil("the page") { m.pages.size == 1 }
        m.rotate()
        assertEquals(listOf("turn1(page)"), m.texts())
        assertEquals(1, m.selected!!.quarterTurns)
        m.applyFilter("Grayscale")
        assertEquals(listOf("Grayscale(turn1(page))"), m.texts())
        m.crop(QUAD)
        assertEquals(listOf("crop(original page)"), m.texts())
        assertNull(m.notice)
    }

    @Test
    fun `book mode stores two pages per shot with the turn of the hold, shows them as a spread and re-splits both`() {
        val m = model()
        m.chooseMode(CaptureMode.BOOK)
        m.shoot("spread", turns = 1)
        waitUntil("two pages") { m.pages.size == 2 }
        assertEquals(listOf(1), turnsSeen)
        assertEquals(listOf("spread left", "spread right"), m.texts())
        m.review()
        val spread = m.spread!!
        assertEquals(1, spread.leftNumber)
        assertEquals(0.5, spread.split, 0.0)

        m.resplit(0.6)
        assertEquals(0.6, m.spread!!.split, 0.0)
        assertEquals(listOf("crop(original spread)", "crop(original spread)"), m.texts())
    }

    @Test
    fun `leaving without saving keeps the pages or discards the document`() {
        val kept = model()
        kept.shoot("keep me")
        waitUntil("the page") { kept.pages.size == 1 }
        var left = 0
        kept.leave(keep = true) { left++ }
        assertEquals(1, left)
        assertEquals(listOf("keep me"), library.documents().single().pages.map { library.file(kept.document!!.id, it.image).readText() })

        val discarded = model()
        discarded.shoot("drop me")
        waitUntil("the page") { discarded.pages.size == 1 }
        discarded.leave(keep = false) { left++ }
        assertEquals(2, left)
        assertNull(library.document(discarded.document!!.id))
        assertEquals(1, library.documents().size)
    }

    @Test
    fun `done saves a PDF without a pending delete and with the password only when one is given`() {
        val m = model()
        m.chooseMode(CaptureMode.MULTI)
        m.shoot("a")
        m.shoot("b")
        waitUntil("two pages") { m.pages.size == 2 && m.canSave }
        assertEquals("${m.document!!.id}.pdf", m.pdfName)
        m.select(m.pages[0].id)
        m.delete()

        val dest = root.resolve("out.pdf").apply { createNewFile() }
        var result: SavedPdf? = null
        m.save(Uri.fromFile(dest), SaveOptions(PdfPageSize.FIT, "")) { result = it }
        assertEquals("b", dest.readText())
        assertEquals(listOf<String?>(null), written)
        assertEquals(1, result!!.pages)
        assertEquals(listOf(result), saved)
        assertEquals("the session ended keeping the document", listOf("b"), m.storedTexts())
    }

    @Test
    fun `a single page shot that cannot be stored stays on the Scanner for retry, and blocks the shutter and the mode until then`() {
        val m = model()
        m.shoot("flaky")
        waitUntil("the failure") { m.failure != null }
        assertEquals(ScanPhase.SCANNER, m.phase)
        assertFalse(m.canShoot)
        assertFalse("a retried shot must not land in another layout", m.canChangeMode)

        m.retry(m.failure!!)
        waitUntil("the retried page in Review") { m.phase == ScanPhase.REVIEW && m.pages.size == 1 }
    }

    @Test
    fun `a failed shot waiting for a retry keeps the mode fixed`() {
        val m = model()
        m.chooseMode(CaptureMode.MULTI)
        m.shoot("bad")
        waitUntil("the failure") { m.failure != null }
        assertEquals(0, m.sessionState.count)
        assertFalse(m.canChangeMode)
        m.dismiss(m.failure!!)
        waitUntil("the dismissal") { m.failure == null }
        assertTrue(m.canChangeMode)
    }

    @Test
    fun `back from an edited single page asks before the retake throws the edit away`() {
        val m = model()
        m.shoot("page")
        waitUntil("the page in Review") { m.phase == ScanPhase.REVIEW && m.pages.size == 1 }
        assertFalse(m.backNeedsPrompt)
        m.rotate()
        assertTrue(m.backNeedsPrompt)
        m.backToScanner()
        assertFalse(m.backNeedsPrompt)
    }

    @Test
    fun `the mode and the hold are read at the shutter, not when the still arrives`() {
        val still = CompletableDeferred<Unit>()
        val m = model(takePicture = { dir ->
            still.await()
            File(dir, "shot.jpg").apply { writeText("spread") }
        })
        m.chooseMode(CaptureMode.BOOK)
        m.deviceQuarterTurns = 1
        m.shutter()
        m.deviceQuarterTurns = 3
        m.chooseMode(CaptureMode.MULTI)
        assertEquals("the mode is fixed while the shot is on its way", CaptureMode.BOOK, m.mode)
        still.complete(Unit)
        waitUntil("the spread") { m.pages.size == 2 }
        assertEquals(listOf(1), turnsSeen)
    }

    @Test
    fun `a move after a delete moves the selected page among the pages that are left`() {
        val m = model()
        m.chooseMode(CaptureMode.MULTI)
        listOf("a", "b", "c", "d").forEach { m.shoot(it) }
        waitUntil("four pages") { m.pages.size == 4 }
        m.select(m.pages[0].id)
        m.delete()
        assertEquals("b", m.fileOf(m.selected!!.image)!!.readText())
        m.move(1)
        assertEquals(listOf("c", "b", "d"), m.storedTexts())
        m.move(-1)
        assertEquals(listOf("b", "c", "d"), m.storedTexts())
        assertNull(m.notice)
    }

    @Test
    fun `a committed delete stays hidden until the library has removed the page, and later numbers do not count it`() {
        val m = model()
        m.chooseMode(CaptureMode.MULTI)
        listOf("a", "b", "c").forEach { m.shoot(it) }
        waitUntil("three pages") { m.pages.size == 3 }
        m.select(m.pages[0].id)
        m.delete()
        io.held = true
        m.select(m.pages[0].id)
        assertEquals(listOf("b", "c"), m.texts())
        assertEquals(2, m.count)
        m.delete()
        assertEquals(1, m.deleted!!.number)
        io.release()
        assertEquals(listOf("b", "c"), m.storedTexts())
        assertEquals(listOf("c"), m.texts())
    }

    @Test
    fun `a session that finishes starting after the flow closed is still ended`() {
        val starting = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        var started: ScanSession? = null
        val thread = Executors.newSingleThreadExecutor()
        try {
            val m = model(io = thread.asCoroutineDispatcher(), startSession = {
                starting.countDown()
                proceed.await(10, TimeUnit.SECONDS)
                ScanSession(library, root.resolve("staging/late")).also { started = it }
            })
            assertTrue(starting.await(10, TimeUnit.SECONDS))
            m.close()
            proceed.countDown()
            waitUntil("the late session to end") { started?.state?.value?.finished == true }
            assertFalse(root.resolve("staging/late").exists())
        } finally {
            thread.shutdown()
        }
    }

    private fun scanFolder(dir: File) = ScanFolder(FileFolderStore(dir), library, root.resolve("library"), WorkingCopies(root.resolve("bindings")))

    @Test
    fun `done with a folder writes the PDF at its top level and the pages under Scan sources, then ends keeping the pages`() {
        val dir = root.resolve("folder").apply { mkdirs() }
        val m = model()
        m.chooseMode(CaptureMode.MULTI)
        m.shoot("a")
        m.shoot("b")
        waitUntil("two pages") { m.pages.size == 2 && m.canSave }
        m.pendingSave = SaveOptions(PdfPageSize.FIT, "secret")

        var result: WriteResult? = null
        m.saveToFolder(scanFolder(dir), SaveOptions(PdfPageSize.FIT, "secret")) { result = it }
        val name = (result as WriteResult.Written).name
        assertEquals("a|b", dir.resolve("$name.pdf").readText())
        assertTrue(dir.resolve("Scan sources/$name/document.json").isFile)
        assertEquals(listOf<String?>("secret"), written)
        assertNull("the options are used once", m.pendingSave)
        assertTrue("nothing went through the picker", saved.isEmpty())
        assertFalse(m.busy)
        assertEquals("the session ended keeping the document", listOf("a", "b"), m.storedTexts())
    }

    @Test
    fun `a folder out of reach keeps Review and its pages, and a failed write says so`() {
        val gone = root.resolve("gone")
        val m = model()
        m.shoot("a")
        waitUntil("the page") { m.pages.size == 1 && m.canSave }

        var result: WriteResult? = null
        m.saveToFolder(scanFolder(gone), SaveOptions(PdfPageSize.A4, "")) { result = it }
        assertEquals(WriteResult.FolderUnavailable, result)
        assertNull("the caller asks for the folder again", m.notice)
        assertFalse(m.busy)
        assertEquals(1, m.pages.size)
        assertTrue("the session still runs", m.canSave)

        assemblyFails = true
        m.saveToFolder(scanFolder(root.resolve("folder").apply { mkdirs() }), SaveOptions(PdfPageSize.A4, "")) { result = it }
        assertEquals(WriteResult.Failed, result)
        assertEquals(ScanNotice.SAVE_FAILED, m.notice)
        assertEquals(1, m.pages.size)
    }

    @Test
    fun `the save options wait in the flow's model while the picker is open`() {
        val m = model()
        m.pendingSave = SaveOptions(PdfPageSize.LEGAL, "secret")
        assertEquals(SaveOptions(PdfPageSize.LEGAL, "secret"), m.pendingSave)
        m.shoot("a")
        waitUntil("the page") { m.pages.size == 1 && m.canSave }
        val dest = root.resolve("out.pdf").apply { createNewFile() }
        m.save(Uri.fromFile(dest), SaveOptions(PdfPageSize.LEGAL, "secret")) {}
        assertNull("the options are used once", m.pendingSave)
        assertEquals(listOf<String?>("secret"), written)
    }

    @Test
    fun `crop starts from the page's detected quad, a book page's half of the spread, and a re-crop's quad`() {
        val m = model()
        m.chooseMode(CaptureMode.MULTI)
        m.shoot("page")
        waitUntil("the page") { m.pages.size == 1 }
        assertEquals(QUAD, m.cropStart)
        m.crop(FULL)
        assertEquals(FULL, m.cropStart)

        val b = model()
        b.chooseMode(CaptureMode.BOOK)
        b.shoot("spread", turns = 1)
        waitUntil("the spread") { b.pages.size == 2 }
        b.select(b.pages[0].id)
        assertEquals(BookSplit.page(QUAD, BookPage.LEFT, 0.5, 1), b.cropStart)
    }

    @Test
    fun `picked images open Review as a multi page scan with one page each`() {
        val m = model()
        val picked = listOf("one", "two").map { text -> Uri.fromFile(root.resolve("$text.jpg").apply { writeText(text) }) }
        m.importImages(context.contentResolver, picked)
        assertEquals(ScanPhase.REVIEW, m.phase)
        assertEquals(CaptureMode.MULTI, m.mode)
        waitUntil("the imported pages") { m.pages.size == 2 }
        assertEquals(listOf("one", "two"), m.texts())
    }

    @Test
    fun `images picked before the session has started are imported once it has`() {
        io.held = true
        val m = model()
        val picked = listOf(Uri.fromFile(root.resolve("early.jpg").apply { writeText("early") }))
        m.importImages(context.contentResolver, picked)
        io.release()
        waitUntil("the imported page") { m.pages.size == 1 }
        assertEquals(listOf("early"), m.texts())
    }

    @Test
    fun `the auto-capture choice is remembered for the next scan`() {
        val m = model()
        assertTrue(m.autoCapture)
        m.toggleAutoCapture()
        assertFalse(m.autoCapture)
        assertFalse(ScanPreferences(context).autoCapture)
        assertFalse(model().autoCapture)
    }

    private val searchable = TextOptions(OcrLanguage.ENGLISH, OcrReading.RECEIPTS, txt = false)

    /** A Multi page flow with a page per text, stored and ready to save. */
    private fun ready(vararg pages: String): ScanFlowModel {
        val m = model()
        m.chooseMode(CaptureMode.MULTI)
        for (p in pages) m.shoot(p)
        waitUntil("the pages") { m.pages.size == pages.size && m.canSave }
        return m
    }

    private fun out(name: String = "out.pdf"): Uri = Uri.fromFile(root.resolve(name).apply { createNewFile() })

    private fun cached(m: ScanFlowModel): List<String> = root.resolve("library/${m.document!!.id}").list().orEmpty().filter { it.startsWith("ocr_") }

    @Test
    fun `a searchable save reads every page with the chosen settings, counting them, and gives each page its text`() {
        val m = ready("a", "b", "c")
        val seen = mutableListOf<ReadingProgress?>()
        engine.onRead = { seen += m.reading }
        var result: SavedPdf? = null
        m.save(out(), SaveOptions(PdfPageSize.LEGAL, "", searchable)) { result = it }
        assertEquals(3, result!!.pages)
        assertEquals(listOf(ReadingProgress(1, 3), ReadingProgress(2, 3), ReadingProgress(3, 3)), seen)
        assertEquals(setOf(355.6 to OcrReading.RECEIPTS), engine.reads.map { it.longEdgeMm to it.reading }.toSet())
        assertEquals(setOf(OcrLanguage.ENGLISH), engine.reads.map { it.language }.toSet())
        assertEquals(listOf(listOf<String?>("a", "b", "c")), texts)
        assertNull(m.reading)
        assertEquals("one engine per save, closed after", 1, engine.closes)
        assertTrue("the cache goes once the PDF is saved", cached(m).isEmpty())
        assertNull("no .txt asked for", m.textToSave)
    }

    @Test
    fun `without Make searchable nothing is read`() {
        val m = ready("a")
        m.save(out(), SaveOptions(PdfPageSize.A4, "")) {}
        assertTrue(engine.reads.isEmpty())
        assertEquals(listOf(listOf<String?>(null)), texts)
    }

    @Test
    fun `Save without text cancels the page being read and saves the PDF without a text layer or txt`() {
        val m = ready("a", "b")
        engine.onRead = { if (it == "a") m.saveWithoutText() }
        var result: SavedPdf? = null
        m.save(out(), SaveOptions(PdfPageSize.A4, "", searchable.copy(txt = true))) { result = it }
        assertEquals(2, result!!.pages)
        assertEquals(1, engine.cancels)
        assertEquals(listOf("a"), engine.reads.map { it.text })
        assertEquals(listOf(listOf<String?>(null, null)), texts)
        assertNull(m.textToSave)
        assertTrue(m.handwritten.isEmpty())
    }

    @Test
    fun `going to the background lets the page being read finish, then waits, and coming back resumes`() {
        val m = ready("a", "b", "c")
        engine.onRead = { if (it == "a") m.pauseReading() }
        var result: SavedPdf? = null
        m.save(out(), SaveOptions(PdfPageSize.A4, "", searchable)) { result = it }
        assertEquals(listOf("a"), engine.reads.map { it.text })
        assertEquals(0, engine.cancels)
        assertTrue(m.busy)
        assertNull(result)
        assertEquals("page a was the last one started", ReadingProgress(1, 3), m.reading)
        assertEquals("page a is kept for a restart", 1, cached(m).size)
        m.resumeReading()
        assertEquals(listOf("a", "b", "c"), engine.reads.map { it.text })
        assertEquals(3, result!!.pages)
    }

    @Test
    fun `a save that fails keeps the pages read, and the next one reads only the rest`() {
        val m = ready("a", "b")
        assemblyFails = true
        m.save(out(), SaveOptions(PdfPageSize.A4, "", searchable)) {}
        assertEquals(ScanNotice.SAVE_FAILED, m.notice)
        assertEquals(2, cached(m).size)
        assemblyFails = false
        m.save(out("again.pdf"), SaveOptions(PdfPageSize.A4, "", searchable)) {}
        assertEquals("nothing read twice", 2, engine.reads.size)
        assertEquals(listOf<String?>("a", "b"), texts.last())
    }

    @Test
    fun `with the txt option the picker save waits for a second picker, whose file gets the text of every page`() {
        val m = ready("a", "scribble")
        var saved = 0
        m.save(out(), SaveOptions(PdfPageSize.A4, "", searchable.copy(txt = true))) { saved++ }
        assertEquals(1, saved)
        val (name, text) = m.textToSave!!
        assertEquals("${m.document!!.id}.txt", name)
        assertEquals("words below 40 stay out of the txt", "a\n\u000C", text)
        assertEquals("the page that looks handwritten", listOf(2), m.handwritten)
        val txt = root.resolve("out.txt").apply { createNewFile() }
        var done = 0
        m.saveText(Uri.fromFile(txt)) { done++ }
        assertEquals(1, done)
        assertEquals(text, txt.readText())
        assertNull(m.textToSave)
    }

    @Test
    fun `cancelling the txt picker just skips the txt`() {
        val m = ready("a")
        m.save(out(), SaveOptions(PdfPageSize.A4, "", searchable.copy(txt = true))) {}
        m.skipText()
        assertNull(m.textToSave)
        assertEquals("the session ended keeping the document", listOf("a"), m.storedTexts())
    }

    @Test
    fun `a folder save writes the txt beside the PDF, with the same base name, only when asked`() {
        val dir = root.resolve("folder").apply { mkdirs() }
        val m = ready("a", "b")
        var result: WriteResult? = null
        m.saveToFolder(scanFolder(dir), SaveOptions(PdfPageSize.A4, "", searchable.copy(txt = true))) { result = it }
        val name = (result as WriteResult.Written).name
        assertEquals(listOf<String?>("a", "b"), texts.single())
        assertEquals("a\n\u000Cb\n", dir.resolve("$name.txt").readText())
        assertTrue(dir.resolve("$name.pdf").isFile)
        assertTrue(cached(m).isEmpty())

        val other = root.resolve("other").apply { mkdirs() }
        val n = ready("c")
        n.saveToFolder(scanFolder(other), SaveOptions(PdfPageSize.A4, "", searchable)) { result = it }
        assertEquals(listOf("${(result as WriteResult.Written).name}.pdf"), other.list()!!.filter { !it.startsWith("Scan") })
    }

    private companion object {
        val QUAD = Quad(Pt(0.1, 0.1), Pt(0.9, 0.1), Pt(0.9, 0.9), Pt(0.1, 0.9))
        val FULL = Quad(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0))
    }
}
