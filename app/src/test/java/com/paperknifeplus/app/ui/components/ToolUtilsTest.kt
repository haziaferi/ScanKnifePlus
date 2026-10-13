package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolUtilsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun decryptedCopies() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("decrypted_") }

    @Test
    fun `decryptToCache gives null and writes nothing when the provider has no stream`() = runBlocking {
        assertNull(decryptToCache(context, TestPdfs.nullStreamUri(), TestPdfs.USER_PASSWORD))
        assertEquals(emptyList<File>(), decryptedCopies())
    }

    @Test
    fun `decryptToCache writes a unique, unencrypted copy`() = runBlocking {
        val source = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked.pdf")
        val first = decryptToCache(context, source, TestPdfs.USER_PASSWORD)!!
        val second = decryptToCache(context, source, TestPdfs.USER_PASSWORD)!!
        assertTrue(first != second)
        PDDocument.load(File(first.path!!)).use { assertEquals(2, it.numberOfPages) }
        assertNull(decryptToCache(context, source, "wrong"))
        assertEquals(2, decryptedCopies().size)
    }

    @Test
    fun `a decrypted copy that fails to save is deleted`() {
        val document = PDDocument.load(TestPdfs.plain())
        document.close() // Saving a closed document throws once the file exists.
        assertThrows(IOException::class.java) { saveDecryptedCopy(document, context.cacheDir) }
        assertEquals(emptyList<File>(), decryptedCopies())
    }

    @Test
    fun `PDF to Image deletes its decrypted copy when the export fails`() {
        val source = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked.pdf")
        assertThrows(IOException::class.java) {
            runBlocking {
                convertPdfToImages(context, source, TestPdfs.nullStreamUri(), TestPdfs.USER_PASSWORD, listOf(0, 1), "JPG", "Standard") { _, _ ->
                    throw IOException("stopped")
                }
            }
        }
        assertEquals(emptyList<File>(), decryptedCopies())
    }

    @Test
    fun `saveAndFlush fails on a missing output stream and still closes the document`() {
        val document = PDDocument.load(TestPdfs.plain())
        assertThrows(IOException::class.java) { saveAndFlush(context, document, TestPdfs.nullStreamUri()) }
        assertTrue(document.document.isClosed)
    }

    @Test
    fun `saveAndFlush writes the document as it is, with no Creator or Producer of its own`() {
        val output = File(context.cacheDir, "out.pdf")
        val saved = PDDocument.load(TestPdfs.plain(2))
        saveAndFlush(context, saved, Uri.fromFile(output))
        assertTrue(saved.document.isClosed)

        val expected = PDDocument.load(TestPdfs.plain(2)).use { ByteArrayOutputStream().also { out -> it.save(out) }.toByteArray() }
        // PDFBox gives every save a fresh /ID; everything else must match.
        fun withoutId(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1).replace(Regex("/ID ?.<[0-9A-Fa-f]*> ?<[0-9A-Fa-f]*>."), "/ID")
        assertEquals(withoutId(expected), withoutId(output.readBytes()))
        PDDocument.load(output).use {
            assertNull(it.documentInformation.creator)
            assertNull(it.documentInformation.producer)
        }
    }

    @Test
    fun `saveAndFlush keeps a document's own Creator and Producer`() {
        val set = File(context.cacheDir, "set.pdf")
        val document = PDDocument.load(TestPdfs.plain()).apply {
            documentInformation.creator = "Writer"
            documentInformation.producer = "Press"
        }
        saveAndFlush(context, document, Uri.fromFile(set))
        PDDocument.load(set).use {
            assertEquals("Writer", it.documentInformation.creator)
            assertEquals("Press", it.documentInformation.producer)
        }
    }

    @Test
    fun `saveAndFlush fills a blank author with the default author unless defaults are off`() {
        PreferencesManager.setDefaultAuthor(context, "Ada")
        try {
            fun authorAfterSave(name: String, author: String?, applyDefaults: Boolean): String? {
                val out = File(context.cacheDir, name)
                val document = PDDocument.load(TestPdfs.plain()).apply { if (author != null) documentInformation.author = author }
                saveAndFlush(context, document, Uri.fromFile(out), applyDefaults)
                return PDDocument.load(out).use { it.documentInformation.author }
            }
            assertEquals("Ada", authorAfterSave("blank.pdf", null, applyDefaults = true))
            assertEquals("Grace", authorAfterSave("set.pdf", "Grace", applyDefaults = true))
            assertNull(authorAfterSave("off.pdf", null, applyDefaults = false))
        } finally {
            PreferencesManager.setDefaultAuthor(context, "")
        }
    }

    @Test
    fun `formatSize keeps its text`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("1.5 KB", formatSize(1536))
            assertEquals("0 B", formatSize(0))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the page count of a protected file needs its password, even after it was opened with it`() = runBlocking {
        val source = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 3), "counted.pdf")
        assertEquals(3, getPageCount(context, source, TestPdfs.USER_PASSWORD))
        assertEquals(0, getPageCount(context, source, "wrong"))
        assertEquals(0, getPageCount(context, source, null))
    }

    /** A Flate-free 1-bit image of [width] x [height] pixels; uncompressed, so a JPEG of it would be far smaller. */
    private fun oneBitImage(document: PDDocument, width: Int, height: Int, stencil: Boolean): PDImageXObject {
        val bytes = ByteArray((width + 7) / 8 * height) { if (it % 7 == 0) 0x0F else 0x00 }
        return PDImageXObject(PDStream(document, bytes.inputStream()), null).apply {
            this.width = width
            this.height = height
            bitsPerComponent = 1
            if (stencil) this.isStencil = true else colorSpace = PDDeviceGray.INSTANCE
        }
    }

    private fun pdfWith(vararg images: (PDDocument) -> PDImageXObject): ByteArray = PDDocument().use { doc ->
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs -> images.forEachIndexed { i, image -> cs.drawImage(image(doc), 10f + 100f * i, 10f, 90f, 90f) } }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun compress(input: ByteArray, level: String): File {
        val source = TestPdfs.write(context.cacheDir, input, "to-compress.pdf")
        val output = File(context.cacheDir, "compressed.pdf")
        runBlocking { compressPdf(context, source, Uri.fromFile(output), null, level) { _, _ -> } }
        return output
    }

    private fun rawBytes(image: PDImageXObject) = image.cosObject.createRawInputStream().use { it.readBytes() }

    @Test
    fun `Compress keeps 1-bit images and stencil masks as they are`() {
        val input = pdfWith({ oneBitImage(it, 800, 800, stencil = false) }, { oneBitImage(it, 800, 800, stencil = true) })
        val before = PDDocument.load(input).use { doc ->
            val resources = doc.getPage(0).resources
            resources.xObjectNames.map { name -> (resources.getXObject(name) as PDImageXObject).let { Triple(it.isStencil, it.cosObject.filters, rawBytes(it)) } }
        }
        val after = PDDocument.load(compress(input, "Extreme")).use { doc ->
            val resources = doc.getPage(0).resources
            resources.xObjectNames.map { name -> (resources.getXObject(name) as PDImageXObject).let { Triple(it.isStencil, it.cosObject.filters, rawBytes(it)) } }
        }
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (b, a) ->
            assertEquals(b.first, a.first)
            assertEquals(b.second, a.second)
            assertArrayEquals(b.third, a.third)
        }
        assertTrue(after.any { it.first })
    }

    @Test
    fun `Compress keeps a small well-compressed JPEG at Basic and the file does not grow`() {
        // Detailed content saved at low quality, so re-encoding at Basic's 0.9 would make it larger.
        val random = java.util.Random(7)
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            setHasAlpha(false)
            setPixels(IntArray(200 * 200) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }, 0, 200, 0, 0, 200, 200)
        }
        val input = pdfWith({ JPEGFactory.createFromImage(it, bitmap, 0.3f) })
        val before = PDDocument.load(input).use { doc -> rawBytes(doc.getPage(0).resources.let { r -> r.getXObject(r.xObjectNames.first()) as PDImageXObject }) }

        val output = compress(input, "Basic")
        val after = PDDocument.load(output).use { doc -> doc.getPage(0).resources.let { r -> r.getXObject(r.xObjectNames.first()) as PDImageXObject }.let { it.cosObject.filters to rawBytes(it) } }
        assertEquals(COSName.DCT_DECODE, after.first)
        assertArrayEquals(before, after.second)
        val resaved = PDDocument.load(input).use { doc ->
            doc.documentInformation.creator = "ScanKnife+"
            doc.documentInformation.producer = "ScanKnife+"
            ByteArrayOutputStream().also { doc.save(it) }.size()
        }
        assertTrue(output.length() <= resaved)
    }

    @Test
    fun `the start-up sweep leaves recent decrypted copies alone`() {
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        val fresh = File(context.cacheDir, "decrypted_fresh.pdf").apply { writeText("x"); setLastModified(now - day / 2) }
        val stale = File(context.cacheDir, "decrypted_stale.pdf").apply { writeText("x"); setLastModified(now - 2 * day) }
        assertEquals(1, sweepDecryptedCopies(context.cacheDir, minAgeMs = day, now = now))
        assertTrue(fresh.exists())
        assertFalse(stale.exists())
    }
}
