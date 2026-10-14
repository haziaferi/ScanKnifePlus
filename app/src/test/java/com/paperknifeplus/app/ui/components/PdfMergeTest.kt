package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/** Merge appends whole files or the ranges given per file, in order. */
@RunWith(RobolectricTestRunner::class)
class PdfMergeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun merge(vararg sources: MergeSource): ByteArray {
        val output = ByteArrayOutputStream()
        val pages = mergePdfs(sources.toList(), output)
        return output.toByteArray().also { bytes -> assertEquals(pages, PDDocument.load(bytes).use { it.numberOfPages }) }
    }

    private fun source(bytes: ByteArray, ranges: List<IntRange>? = null) = MergeSource({ bytes.inputStream() }, ranges)

    /** The page of [TestPdfs.plain] [page] shows, read from the y of its rectangle, or the structured marker number as 100 + n. */
    private fun PDPage.label(): Int {
        val content = contents.readBytes().toString(Charsets.ISO_8859_1)
        Regex("STRUCTURED-PAGE-(\\d)").find(content)?.let { return 100 + it.groupValues[1].toInt() }
        return 700 - Regex("72 (\\d+) 100 20 re").find(content)!!.groupValues[1].toInt()
    }

    private fun PDDocument.outline(): Map<String, Int> =
        documentCatalog.documentOutline.children().associate { item: PDOutlineItem -> item.title to pages.indexOf(item.findDestinationPage(this)) }

    @Test
    fun `whole files are appended in order with their outline`() {
        PDDocument.load(merge(source(TestPdfs.plain(2)), source(TestPdfs.structured()))).use { doc ->
            assertEquals(listOf(0, 1, 101, 102, 103, 104), doc.pages.map { it.label() })
            assertEquals(mapOf("A" to 2, "B" to 3, "C" to 4, "D" to 5), doc.outline())
        }
    }

    @Test
    fun `each file gives its ranges in the order listed, a repeated page as its own copy`() {
        val bytes = merge(source(TestPdfs.structured(), listOf(3..3, 0..1)), source(TestPdfs.plain(3), listOf(2..2, 0..0, 0..0)))
        PDDocument.load(bytes).use { doc ->
            assertEquals(listOf(104, 101, 102, 2, 0, 0), doc.pages.map { it.label() })
            assertNotSame(doc.getPage(4).cosObject, doc.getPage(5).cosObject)
            // C led to the page left out, so it went; the others follow their pages.
            assertEquals(mapOf("A" to 1, "B" to 2, "D" to 0), doc.outline())
        }
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(TestPdfs.structuredMarker(3)))
    }
}
