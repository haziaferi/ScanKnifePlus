package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

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

    @Test
    fun `a repeated page keeps what it inherits from an intermediate pages node`() {
        // Move the root's inherited attributes and pages under one intermediate /Pages node.
        val nested = PDDocument.load(TestPdfs.inheritedAttributes(2)).use { doc ->
            val root = doc.pages.cosObject
            val middle = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.PAGES)
                setItem(COSName.PARENT, root)
                setInt(COSName.COUNT, 2)
                for (key in listOf(COSName.KIDS, COSName.RESOURCES, COSName.MEDIA_BOX, COSName.ROTATE)) {
                    setItem(key, root.getDictionaryObject(key))
                    if (key != COSName.KIDS) root.removeItem(key)
                }
            }
            doc.pages.forEach { it.cosObject.setItem(COSName.PARENT, middle) }
            root.setItem(COSName.KIDS, COSArray().apply { add(middle) })
            ByteArrayOutputStream().also(doc::save).toByteArray()
        }
        PDDocument.load(merge(source(nested, listOf(1..1, 1..1)))).use { doc ->
            assertEquals(2, doc.numberOfPages)
            for (page in doc.pages) {
                assertEquals(TestPdfs.INHERITED_MEDIA_BOX.toString(), page.mediaBox.toString())
                assertEquals(TestPdfs.INHERITED_ROTATION, page.rotation)
                assertTrue(page.resources.fontNames.contains(TestPdfs.INHERITED_FONT))
            }
        }
    }

    @Test
    fun `the Merge screen's files are merged whole, an unlocked one from its decrypted copy`() {
        val plain = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "merge-a.pdf")
        val locked = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(3), "merge-locked.pdf")
        val decrypted = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "merge-decrypted.pdf")
        val files = listOf(
            MergeFile(1, plain, "a.pdf", "1 KB", isLocked = false),
            MergeFile(2, locked, "b.pdf", "1 KB", isLocked = true, isUnlocked = true, decryptedUri = decrypted)
        )
        val output = File(context.cacheDir, "merge-out.pdf").apply { delete() }
        val progress = mutableListOf<Int>()
        assertEquals(5, mergeFiles(context, files, Uri.fromFile(output)) { current, total -> progress += current * 10 + total })
        assertEquals(listOf(12, 22), progress)
        PDDocument.load(output).use { doc -> assertEquals(listOf(0, 1, 0, 1, 2), doc.pages.map { it.label() }) }
    }
}
