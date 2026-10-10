package com.haziaferi.scanknifeplus.scan.export

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.export.ExportQuality
import com.haziaferi.scanknifeplus.scanner.export.PdfLayout
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.io.InputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real PDFs from real pages, read back with pdfbox: what is embedded, where it is drawn, and how save and share deliver it. */
@RunWith(AndroidJUnit4::class)
class PdfExportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "export-test-${System.nanoTime()}")
    private val library = ScanLibrary(File(root, "scans"))
    private val saved = mutableListOf<SavedPdf>()
    private val export = ScanExport(context, library, ScanPdfExporter(library, File(root, "work")), onSaved = { saved += it })

    private val shareDir = ScanFiles.shareDir(context)
    private val sharedBefore = shareDir.list().orEmpty().toSet()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
        shareDir.listFiles().orEmpty().filter { it.name !in sharedBefore }.forEach { it.deleteRecursively() }
    }

    private fun photo(width: Int, height: Int, color: Int): ImageSource {
        val file = File(root, "photo-${System.nanoTime()}.jpg")
        root.mkdirs()
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        b.recycle()
        return ImageSource.of(file)
    }

    /** A document with a portrait and a landscape page. */
    private fun document(): String {
        val id = library.create("Tax return: 2026/27").id
        library.addCapture(id, photo(1200, 1600, Color.rgb(200, 180, 150)), null, keepOriginal = false)
        library.addCapture(id, photo(3000, 2000, Color.rgb(90, 120, 200)), null, keepOriginal = false)
        return id
    }

    private fun images(page: PDPage): List<PDImageXObject> = page.resources.xObjectNames.map { page.resources.getXObject(it) as PDImageXObject }

    /** The `a 0 0 d e f cm` before the image is drawn: width, height, x, y. */
    private fun drawn(page: PDPage): List<Double> {
        val content = page.contents.use { String(it.readBytes(), Charsets.ISO_8859_1) }
        val m = Regex("""([-\d.]+) 0 0 ([-\d.]+) ([-\d.]+) ([-\d.]+) cm\s*/\w+ Do""").find(content) ?: error("no image drawn in: $content")
        return m.groupValues.drop(1).map { it.toDouble() }
    }

    private fun load(input: InputStream): PDDocument = input.use { PDDocument.load(it) }

    @Test
    fun theHighPresetEmbedsEachStoredPageByteForByteWhereOpenScanDrawsIt() {
        val id = document()
        val uri = export.share(id, ExportQuality.HIGH, PdfPageSize.A4)!!
        val pages = library.document(id)!!.pages
        load(context.contentResolver.openInputStream(uri)!!).use { pdf ->
            assertEquals(2, pdf.numberOfPages)
            for ((i, page) in pdf.pages.withIndex()) {
                assertEquals(PdfPageSize.A4.width.toFloat(), page.mediaBox.width, 0.001f)
                assertEquals(PdfPageSize.A4.height.toFloat(), page.mediaBox.height, 0.001f)
                val image = images(page).single()
                val stored = library.file(id, pages[i].image).readBytes()
                assertArrayEquals("page $i is embedded unchanged", stored, image.cosObject.createRawInputStream().use { it.readBytes() })
                val rect = PdfLayout.imageRect(PdfPageSize.A4, image.width, image.height)
                val (w, h, x, y) = drawn(page)
                assertEquals(rect.width, w, 0.01)
                assertEquals(rect.height, h, 0.01)
                assertEquals(rect.x, x, 0.01)
                assertEquals(rect.y, y, 0.01)
            }
        }
    }

    @Test
    fun aSmallerPresetCapsEveryPageAndUsesThePageSize() {
        val id = document()
        val uri = export.share(id, ExportQuality.ULTRA_LOW, PdfPageSize.LEGAL)!!
        load(context.contentResolver.openInputStream(uri)!!).use { pdf ->
            for (page in pdf.pages) {
                assertEquals(PdfPageSize.LEGAL.height.toFloat(), page.mediaBox.height, 0.001f)
                val image = images(page).single()
                assertEquals(900, maxOf(image.width, image.height))
            }
        }
    }

    @Test
    fun aShareCopyIsNamedAfterTheDocumentAndGrantedToTheReceiver() {
        val id = document()
        val uri = export.share(id)!!
        assertTrue("$uri", uri.path!!.endsWith("/Tax_return_202627.pdf"))
        val intent = ScanExport.shareIntent(uri, "Tax return: 2026/27")
        assertEquals("application/pdf", intent.type)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // A second share of the same document does not overwrite the first, which a receiving app may still be reading.
        val again = export.share(id)!!
        assertTrue(uri != again)
        assertNotNull(context.contentResolver.openInputStream(uri)!!.use { it.read() })
    }

    @Test
    fun shareCopiesAreRemovedOnceADayOld() {
        val now = System.currentTimeMillis()
        val old = File(shareDir, "pdf-test-old").apply { mkdirs(); File(this, "a.pdf").writeText("x"); setLastModified(now - 25L * 60 * 60 * 1000) }
        val recent = File(shareDir, "pdf-test-recent").apply { mkdirs(); File(this, "b.pdf").writeText("x"); setLastModified(now - 60L * 60 * 1000) }
        assertNotNull(export.share(document()))
        assertTrue("an old copy is removed", !old.exists())
        assertTrue("a recent copy stays", File(recent, "b.pdf").exists())
    }

    @Test
    fun saveWritesTheChosenDestinationAndReportsIt() {
        val id = document()
        val dest = Uri.fromFile(File(root, "picked.pdf"))
        val result = export.save(id, dest, ExportQuality.MEDIUM, PdfPageSize.LETTER)!!
        assertEquals(2, result.pages)
        assertEquals(listOf(result), saved)
        load(File(root, "picked.pdf").inputStream()).use { assertEquals(2, it.numberOfPages) }
        assertNull(export.save("missing", Uri.fromFile(File(root, "other.pdf"))))
        assertEquals(1, saved.size)
    }
}
