package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/** Split by bookmarks and by ranges: where parts begin and end, what each part keeps, and how the files are named. */
@RunWith(RobolectricTestRunner::class)
class PdfSplitTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun part(first: Int, last: Int, title: String? = null) = SplitPart(first..last, title)

    // Outline order is not page order: "Two" points back before "One".
    private val outline = listOf(
        Bookmark("Intro", 2),
        Bookmark("One", 4),
        Bookmark("One.1", 5, depth = 1),
        Bookmark("No target", null),
        Bookmark("Past the end", 99),
        Bookmark("Two", 3),
        Bookmark("Deep", 7, depth = 2)
    )

    @Test
    fun `each top-level bookmark starts a part that ends before the next one in page order`() {
        val split = bookmarkParts(outline, pageCount = 10)
        assertEquals(listOf(part(0, 1), part(2, 2, "Intro"), part(3, 3, "Two"), part(4, 9, "One")), split.parts)
        assertEquals(2, split.skipped)
    }

    @Test
    fun `a deeper level adds its bookmarks as parts and counts only skipped ones of the chosen levels`() {
        val split = bookmarkParts(outline + Bookmark("Lost child", null, depth = 1), pageCount = 10, maxDepth = 1)
        assertEquals(listOf(part(0, 1), part(2, 2, "Intro"), part(3, 3, "Two"), part(4, 4, "One"), part(5, 9, "One.1")), split.parts)
        assertEquals(3, split.skipped)
    }

    @Test
    fun `bookmarks on one page make one part named after the first of them`() {
        val split = bookmarkParts(listOf(Bookmark("Chapter", 0), Bookmark("Section", 0, depth = 1), Bookmark("Next", 3)), 5, maxDepth = 1)
        assertEquals(listOf(part(0, 2, "Chapter"), part(3, 4, "Next")), split.parts)
    }

    @Test
    fun `no part without a bookmark that leads to a page`() {
        assertEquals(BookmarkSplit(emptyList(), 1), bookmarkParts(listOf(Bookmark("x", null)), 3))
        assertEquals(BookmarkSplit(emptyList(), 0), bookmarkParts(emptyList(), 3))
    }

    @Test
    fun `parts are named as PaperKnife+ PC names them`() {
        assertEquals("01_report.pdf", bookmarkPartFileName("report", 0, 3, null))
        assertEquals("02_Chapter_ 1_2.pdf", bookmarkPartFileName("report", 1, 3, " Chapter: 1/2 "))
        assertEquals("003_report.pdf", bookmarkPartFileName("report", 2, 120, "  "))
        assertEquals("report_part01_p1-3.pdf", rangePartFileName("report", 0, 2, 0..2))
    }

    private fun split(source: ByteArray, password: String, parts: List<SplitPart>): List<ByteArray> {
        val written = mutableListOf<ByteArray>()
        splitPdf({ source.inputStream() }, password, parts) { index, _, document ->
            assertEquals(written.size, index)
            written += ByteArrayOutputStream().also(document::save).toByteArray()
        }
        return written
    }

    private fun contains(bytes: ByteArray, text: String) = String(bytes, Charsets.ISO_8859_1).contains(text)

    /** Outline items as title to page index (-1 for none), nested items as "parent/child". */
    private fun PDDocument.outline(): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        fun walk(items: Iterable<PDOutlineItem>, path: String) {
            for (item in items) {
                result[path + item.title] = item.findDestinationPage(this)?.let { pages.indexOf(it) } ?: -1
                walk(item.children(), "$path${item.title}/")
            }
        }
        documentCatalog.documentOutline?.let { walk(it.children(), "") }
        return result
    }

    @Test
    fun `each part keeps its own pages and the outline entries that lead into it`() {
        val parts = split(TestPdfs.structured(), "", listOf(part(0, 1), part(2, 3)))
        assertEquals(2, parts.size)
        assertTrue(contains(parts[0], TestPdfs.structuredMarker(2)))
        assertFalse(contains(parts[0], TestPdfs.structuredMarker(3)))
        assertTrue(contains(parts[1], TestPdfs.structuredMarker(3)))
        assertFalse(contains(parts[1], TestPdfs.structuredMarker(1)))
        PDDocument.load(parts[0]).use { assertEquals(mapOf("A" to 0, "B" to 1), it.outline()) }
        PDDocument.load(parts[1]).use { doc ->
            assertEquals(2, doc.numberOfPages)
            // B leads out of the part but stays as the heading of B1.
            assertEquals(mapOf("B" to -1, "B/B1" to 0, "C" to 0, "D" to 1), doc.outline())
        }
    }

    @Test
    fun `a part of a range list may repeat pages of another`() {
        val parts = split(TestPdfs.plain(5), "", listOf(part(0, 2), part(2, 4), part(4, 4)))
        assertEquals(listOf(3, 3, 1), parts.map { bytes -> PDDocument.load(bytes).use { it.numberOfPages } })
    }

    @Test
    fun `parts of a protected file stay protected`() {
        val parts = split(TestPdfs.userPassword(3), TestPdfs.USER_PASSWORD, listOf(part(0, 0), part(1, 2)))
        for (bytes in parts) {
            assertTrue(runCatching { PDDocument.load(bytes).close() }.exceptionOrNull() is InvalidPasswordException)
            PDDocument.load(bytes, TestPdfs.USER_PASSWORD).use { assertTrue(it.isEncrypted) }
        }
    }
}
