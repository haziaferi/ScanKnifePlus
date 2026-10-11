package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PdfPagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /** Copies [pages] of [source] into a new document with [append], saves and reloads it. */
    private fun copyPages(source: ByteArray, pages: List<Int>, append: PDDocument.(PDPage) -> Unit): PDDocument {
        val bytes = PDDocument.load(source).use { src ->
            PDDocument().use { target ->
                pages.forEach { target.append(src.getPage(it)) }
                ByteArrayOutputStream().also { target.save(it) }.toByteArray()
            }
        }
        return PDDocument.load(bytes)
    }

    private fun assertKeepsInheritedAttributes(doc: PDDocument, pages: Int) {
        assertEquals(pages, doc.numberOfPages)
        for (page in doc.pages) {
            assertNotNull("font resource", page.resources?.getFont(TestPdfs.INHERITED_FONT))
            assertEquals(TestPdfs.INHERITED_MEDIA_BOX.toString(), page.mediaBox.toString())
            assertEquals(TestPdfs.INHERITED_ROTATION, page.rotation)
        }
    }

    @Test
    fun `pages keep Resources, MediaBox and Rotate inherited from the page tree`() {
        copyPages(TestPdfs.inheritedAttributes(3), listOf(2, 0)) { appendPageFrom(it) }.use { assertKeepsInheritedAttributes(it, 2) }
    }

    @Test
    fun `appendPageFrom also copies pages that carry their own attributes`() {
        copyPages(TestPdfs.plain(2), listOf(1)) { appendPageFrom(it) }.use { doc ->
            assertEquals(1, doc.numberOfPages)
            assertEquals(PDRectangle.LETTER.toString(), doc.getPage(0).mediaBox.toString())
            assertTrue(doc.getPage(0).hasContents())
        }
    }

    @Test
    fun `links to kept pages follow them, links to left-out pages and names are dropped`() {
        val source = TestPdfs.linked(3)
        assertTrue(String(source, Charsets.ISO_8859_1).contains(TestPdfs.LINKED_TARGET_MARKER))

        val bytes = PDDocument.load(source).use { src ->
            PDDocument().use { target ->
                target.appendPagesFrom(listOf(src.getPage(0), src.getPage(1)))
                ByteArrayOutputStream().also { target.save(it) }.toByteArray()
            }
        }
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(TestPdfs.LINKED_TARGET_MARKER))
        PDDocument.load(bytes).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val (toSecond, toLast, named) = doc.getPage(0).annotations.map { it as PDAnnotationLink }
            assertEquals(1, doc.pages.indexOf((toSecond.destination as PDPageDestination).page))
            assertNull(((toLast.action as PDActionGoTo).destination as PDPageDestination).page)
            assertNull(named.destination)
        }
    }

    @Test
    fun `a single appended page keeps no link into the source`() {
        val bytes = PDDocument.load(TestPdfs.linked(3)).use { src ->
            PDDocument().use { target ->
                target.appendPageFrom(src.getPage(0))
                ByteArrayOutputStream().also { target.save(it) }.toByteArray()
            }
        }
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(TestPdfs.LINKED_TARGET_MARKER))
        PDDocument.load(bytes).use { doc -> assertEquals(1, doc.numberOfPages) }
    }

    private fun protect(password: String): File {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "in.pdf")
        val output = File(context.cacheDir, "protected.pdf").apply { delete() }
        runBlocking { protectPdf(context, input, Uri.fromFile(output), null, password) }
        return output
    }

    @Test
    fun `protected output is AES-256 and needs the password`() {
        val output = protect("secret")
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(output).close() }
        PDDocument.load(output, "secret").use { doc ->
            assertEquals(5, doc.encryption.version)
            assertEquals(6, doc.encryption.revision)
            assertEquals(2, doc.numberOfPages)
        }
    }

    @Test
    fun `a non-Latin password round-trips and its lossy form is rejected`() {
        val output = protect("пароль")
        PDDocument.load(output, "пароль").use { assertEquals(2, it.numberOfPages) }
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(output, "??????").close() }
    }

    @Test
    fun `protecting an encrypted input uses its unlock password`() {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(), "locked.pdf")
        val output = File(context.cacheDir, "reprotected.pdf").apply { delete() }
        runBlocking { protectPdf(context, input, Uri.fromFile(output), TestPdfs.USER_PASSWORD, "new") }
        PDDocument.load(output, "new").use { assertEquals(5, it.encryption.version) }
    }
}
