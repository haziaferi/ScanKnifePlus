package com.haziaferi.scanknifeplus.ui.scan

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.scan.ScanPreferences
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.capture.StoredBook
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scan.export.PdfAssembler
import com.haziaferi.scanknifeplus.scan.export.SavedPdf
import com.haziaferi.scanknifeplus.scan.export.ScanExport
import com.haziaferi.scanknifeplus.scan.export.ScanPdfExporter
import com.haziaferi.scanknifeplus.scan.library.BookCaptureWriter
import com.haziaferi.scanknifeplus.scan.library.CaptureWriter
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scan.session.ScanSession
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.haziaferi.scanknifeplus.scanner.filter.Filter
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlinx.coroutines.Dispatchers
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
 * " left" and " right", and every edit writes a marker into the new file, so each test can read back which page is where.
 */
@RunWith(RobolectricTestRunner::class)
class ScanFlowModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root: File = Files.createTempDirectory("flow").toFile()
    private val turnsSeen = Collections.synchronizedList(mutableListOf<Int>())
    private val written = Collections.synchronizedList(mutableListOf<String?>())
    private var now = 1_791_590_400_000L

    private val writer = CaptureWriter { source, _, page, original ->
        val text = source.open().use { it.readBytes().decodeToString() }
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
    private val assembler = PdfAssembler { pages, _, _, dest, password ->
        written += password
        dest.writeText(pages.joinToString("|") { it.readText() })
        true
    }
    private val saved = mutableListOf<SavedPdf>()
    private val export = ScanExport(
        context, library, ScanPdfExporter(library, root.resolve("export"), images = images, assembler = assembler), onSaved = { saved += it }
    )
    private val models = mutableListOf<ScanFlowModel>()

    private fun model(): ScanFlowModel = ScanFlowModel(
        context,
        library = library,
        startSession = { ScanSession(library, root.resolve("staging/s${sessions++}")) },
        export = export,
        main = Dispatchers.Unconfined,
        io = Dispatchers.Unconfined,
    ).also { models += it }

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

    private fun ScanFlowModel.storedTexts(): List<String> = library.document(document!!.id)!!.pages.map { library.file(document!!.id, it.image).readText() }

    @Test
    fun `a single page shot opens Review of that page, and going back takes it back for a retake`() {
        val m = model()
        assertTrue(m.canChangeMode)
        assertTrue(m.shoot("one"))
        assertEquals(ScanPhase.REVIEW, m.phase)
        waitUntil("the page") { m.pages.size == 1 }
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
        m.save(Uri.fromFile(dest), PdfPageSize.FIT, password = "") { result = it }
        assertEquals("b", dest.readText())
        assertEquals(listOf<String?>(null), written)
        assertEquals(1, result!!.pages)
        assertEquals(listOf(result), saved)
        assertEquals("the session ended keeping the document", listOf("b"), m.storedTexts())
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

    private companion object {
        val QUAD = Quad(Pt(0.1, 0.1), Pt(0.9, 0.1), Pt(0.9, 0.9), Pt(0.1, 0.9))
        val FULL = Quad(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0))
    }
}
