package com.haziaferi.scanknifeplus.scan.export

import android.net.Uri
import android.os.Looper
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.capture.StoredCapture
import com.haziaferi.scanknifeplus.scan.library.CaptureWriter
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.export.ExportQuality
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.haziaferi.scanknifeplus.scanner.filter.Filter
import com.paperknifeplus.app.ui.components.SessionManager
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Which files a PDF is built from and how the work folder is handled. The fake assembler records the contents of the pages it was given, and
 * the fake re-encoder writes what it was computed from, e.g. `65@1800(page 1)`.
 */
@RunWith(RobolectricTestRunner::class)
class ScanPdfExporterTest {
    private val root: File = Files.createTempDirectory("export").toFile()
    private val libraryRoot = File(root, "scans")
    private val workRoot = File(root, "work")
    private var now = 1_791_590_400_000L
    private var captured = 0

    private val capture = CaptureWriter { _, _, page, _ ->
        page.writeText("page ${++captured}")
        StoredCapture(page, null, cropped = false, pageWidth = 1, pageHeight = 1)
    }
    private val library = ScanLibrary(libraryRoot, clock = { now }, capture = capture)

    private inner class FakeImages : PageImages {
        var failOn: String? = null

        override fun filter(source: File, filter: Filter, dest: File) = error("not used")

        override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File) = error("not used")

        override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int): Boolean {
            val content = source.readText()
            if (content == failOn) return false
            dest.writeText("$quality@$maxEdge($content)")
            return true
        }
    }

    private inner class FakeAssembler : PdfAssembler {
        var pages: List<String>? = null
        var size: PdfPageSize? = null
        var workDirs = mutableListOf<File>()
        var fail = false

        override fun write(pages: List<File>, size: PdfPageSize, workDir: File, dest: File): Boolean {
            this.pages = pages.map { it.readText() }
            this.size = size
            workDirs += workDir
            if (fail) return false
            dest.writeText("PDF of ${this.pages}")
            return true
        }
    }

    private val images = FakeImages()
    private val assembler = FakeAssembler()
    private val exporter = ScanPdfExporter(library, workRoot, images, assembler, clock = { now })
    private val anySource = ImageSource { ByteArray(0).inputStream() }

    @After
    fun cleanUp() {
        root.deleteRecursively()
        SessionManager.clearHistory()
    }

    private fun document(pages: Int): String {
        val id = library.create().id
        repeat(pages) { library.addCapture(id, anySource, null, keepOriginal = false) }
        return id
    }

    private fun export(id: String, quality: ExportQuality, pageIds: Set<String>? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): Pair<Int?, String?> {
        var delivered: String? = null
        val pages = exporter.writePdf(id, pageIds, quality, PdfPageSize.LETTER, onProgress) { pdf ->
            delivered = pdf.readText()
            true
        }
        return pages to delivered
    }

    @Test
    fun `the high preset embeds the stored pages as they are, in order`() {
        val id = document(3)
        val (pages, pdf) = export(id, ExportQuality.HIGH)
        assertEquals(3, pages)
        assertEquals(listOf("page 1", "page 2", "page 3"), assembler.pages)
        assertEquals(PdfPageSize.LETTER, assembler.size)
        assertEquals("PDF of [page 1, page 2, page 3]", pdf)
    }

    @Test
    fun `a smaller preset re-encodes every page to its quality and size`() {
        val id = document(2)
        val (pages, _) = export(id, ExportQuality.MEDIUM)
        assertEquals(2, pages)
        assertEquals(listOf("65@1800(page 1)", "65@1800(page 2)"), assembler.pages)
    }

    @Test
    fun `if any page cannot be re-encoded, every page goes in as stored`() {
        val id = document(3)
        images.failOn = "page 2"
        val (pages, _) = export(id, ExportQuality.ULTRA_LOW)
        assertEquals(3, pages)
        assertEquals(listOf("page 1", "page 2", "page 3"), assembler.pages)
    }

    @Test
    fun `a selection exports those pages in document order`() {
        val id = document(3)
        val ids = library.document(id)!!.pages.map { it.id }
        val (pages, _) = export(id, ExportQuality.HIGH, setOf(ids[2], ids[0]))
        assertEquals(2, pages)
        assertEquals(listOf("page 1", "page 3"), assembler.pages)
    }

    @Test
    fun `nothing to export fails`() {
        val id = document(2)
        assertNull(export(id, ExportQuality.HIGH, setOf("gone")).first)
        assertNull(export("missing", ExportQuality.HIGH).first)
        assertNull(export(library.create().id, ExportQuality.HIGH).first)
        assertNull(assembler.pages)
    }

    @Test
    fun `a failed PDF or delivery fails the export, and the work folder is always removed`() {
        val id = document(1)
        assembler.fail = true
        assertNull(export(id, ExportQuality.MEDIUM).first)
        assembler.fail = false
        assertNull(exporter.writePdf(id, null, ExportQuality.HIGH, PdfPageSize.A4) { false })
        assertNull(exporter.writePdf(id, null, ExportQuality.HIGH, PdfPageSize.A4) { error("disk full") })
        assertEquals(3, assembler.workDirs.size)
        assertTrue(assembler.workDirs.none { it.exists() })
        assertEquals(emptyList<String>(), workRoot.list()!!.toList())
    }

    @Test
    fun `a page deleted while the PDF is prepared is left out on a second attempt`() {
        val id = document(3)
        val second = library.document(id)!!.pages[1].id
        val (pages, _) = export(id, ExportQuality.HIGH) { done, _ -> if (done == 1) library.deletePage(id, second) }
        assertEquals(2, pages)
        assertEquals(listOf("page 1", "page 3"), assembler.pages)
    }

    @Test
    fun `work folders left by a killed export are removed once a day old`() {
        workRoot.mkdirs()
        val stale = File(workRoot, "pdf-old").apply { mkdirs(); setLastModified(now - 25L * 60 * 60 * 1000) }
        val recent = File(workRoot, "pdf-running").apply { mkdirs(); setLastModified(now - 60_000) }
        export(document(1), ExportQuality.HIGH)
        assertTrue(!stale.exists() && recent.exists())
    }

    @Test
    fun `a saved PDF is added to History on the main thread`() {
        val saved = SavedPdf(Uri.fromFile(File(root, "Invoice.pdf")), 2)
        ScanExport.addToHistory(RuntimeEnvironment.getApplication(), saved)
        assertEquals(0, SessionManager.history.size) // posted, not yet run
        shadowOf(Looper.getMainLooper()).idle()
        val entry = SessionManager.history.single()
        assertEquals("Scan", entry.tool)
        assertEquals("2 pages", entry.size)
        assertEquals(saved.uri, entry.uri)
        assertEquals(2, entry.pageCount)
    }
}
