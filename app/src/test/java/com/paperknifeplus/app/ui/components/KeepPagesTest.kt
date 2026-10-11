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
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/** Delete and Rearrange edit the document in place: what is tied to a page stays with it, and removed pages leave nothing behind. */
@RunWith(RobolectricTestRunner::class)
class KeepPagesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /** Runs [tool] from [source] to a new file and returns the saved bytes. */
    private fun run(source: ByteArray, tool: suspend (Uri, Uri) -> Unit): ByteArray {
        val input = TestPdfs.write(context.cacheDir, source, "keep-in.pdf")
        val output = File(context.cacheDir, "keep-out.pdf").apply { delete() }
        runBlocking { tool(input, Uri.fromFile(output)) }
        return output.readBytes()
    }

    private fun contains(bytes: ByteArray, text: String) = String(bytes, Charsets.ISO_8859_1).contains(text)

    /** The top-level outline items as title to page index (-1 for none), each item's children nested as "title/child". */
    private fun PDDocument.outline(): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        fun walk(items: Iterable<PDOutlineItem>, path: String) {
            for (item in items) {
                result[path + item.title] = item.findDestinationPage(this)?.let { pages.indexOf(it) } ?: -1
                walk(item.children(), "$path${item.title}/")
            }
        }
        walk(documentCatalog.documentOutline.children(), "")
        return result
    }

    /** The index of the page holding each widget of [field]. */
    private fun PDDocument.widgetPages(field: String): List<Int> = documentCatalog.acroForm.getField(field).widgets.map { widget ->
        pages.indexOfFirst { page -> page.annotations.any { it.cosObject === widget.cosObject } }
    }

    private fun PDDocument.namedPage(name: String): Int? =
        documentCatalog.findNamedDestinationPage(PDNamedDestination(name))?.let { pages.indexOf(it.page) }

    @Test
    fun `deleting a page keeps the outline, form, names and labels of the others and cuts what led to it`() {
        val bytes = run(TestPdfs.structured()) { input, output -> deletePages(context, input, output, "", setOf(2)) }
        assertFalse(contains(bytes, TestPdfs.structuredMarker(3)))
        assertTrue(contains(bytes, TestPdfs.structuredMarker(4)))

        PDDocument.load(bytes).use { doc ->
            assertEquals(3, doc.numberOfPages)
            assertEquals(mapOf("A" to 0, "B" to 1, "D" to 2), doc.outline())
            assertEquals(3, doc.documentCatalog.documentOutline.openCount)

            assertEquals(listOf("f0", "group"), doc.documentCatalog.acroForm.fields.map(PDField::getPartialName))
            assertEquals(listOf(0), doc.widgetPages("f0"))
            assertEquals(listOf(1), doc.widgetPages("group"))

            assertEquals(0, doc.namedPage("first"))
            assertNull(doc.namedPage("third"))
            assertEquals(2, doc.namedPage("last"))

            assertEquals(listOf("i", "ii", "2"), doc.documentCatalog.pageLabels.labelsByPageIndices.toList())
            assertNull(doc.documentCatalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION))

            val (toThird, named) = doc.getPage(0).annotations.filterIsInstance<PDAnnotationLink>()
            assertNull((toThird.destination as PDPageDestination).page)
            assertEquals("third", (named.destination as PDNamedDestination).namedDestination)
        }
    }

    @Test
    fun `an outline item whose page is deleted stays as a heading while it has kept children`() {
        val bytes = run(TestPdfs.structured()) { input, output -> deletePages(context, input, output, "", setOf(1)) }
        PDDocument.load(bytes).use { doc ->
            assertEquals(mapOf("A" to 0, "B" to -1, "B/B1" to 1, "C" to 1, "D" to 2), doc.outline())
            val b = doc.documentCatalog.documentOutline.children().first { it.title == "B" }
            assertNull(b.destination)
            assertNull(b.action)
            assertEquals(5, doc.documentCatalog.documentOutline.openCount)
            assertEquals(listOf(1), doc.widgetPages("group"))
            assertEquals(listOf("i", "1", "2"), doc.documentCatalog.pageLabels.labelsByPageIndices.toList())
        }
    }

    @Test
    fun `rearranging keeps outline, form, names and labels attached to the moved pages`() {
        val bytes = run(TestPdfs.structured()) { input, output -> rearrangePages(context, input, output, "", listOf(3, 2, 1, 0)) }
        (1..4).forEach { assertTrue(contains(bytes, TestPdfs.structuredMarker(it))) }

        PDDocument.load(bytes).use { doc ->
            assertEquals(4, doc.numberOfPages)
            assertEquals(mapOf("A" to 3, "B" to 2, "B/B1" to 1, "C" to 1, "D" to 0), doc.outline())
            assertEquals(listOf("f0", "f2", "group"), doc.documentCatalog.acroForm.fields.map(PDField::getPartialName))
            assertEquals(listOf(3), doc.widgetPages("f0"))
            assertEquals(listOf(1), doc.widgetPages("f2"))
            assertEquals(listOf(2, 1), doc.widgetPages("group"))
            assertEquals(3, doc.namedPage("first"))
            assertEquals(1, doc.namedPage("third"))
            assertEquals(0, doc.namedPage("last"))
            assertEquals(listOf("2", "1", "ii", "i"), doc.documentCatalog.pageLabels.labelsByPageIndices.toList())
            val openAction = doc.documentCatalog.openAction as PDPageDestination
            assertEquals(1, doc.pages.indexOf(openAction.page))
        }
    }

    @Test
    fun `pages keep attributes inherited from a page tree node that goes away`() {
        // Pages root -> one intermediate node carrying the attributes -> the pages.
        val source = PDDocument.load(TestPdfs.inheritedAttributes(3)).use { doc ->
            val root = doc.pages.cosObject
            val kids = root.getDictionaryObject(COSName.KIDS) as COSArray
            val node = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.PAGES)
                setItem(COSName.PARENT, root)
                setItem(COSName.KIDS, kids)
                setInt(COSName.COUNT, 3)
                listOf(COSName.RESOURCES, COSName.MEDIA_BOX, COSName.ROTATE).forEach { key ->
                    setItem(key, root.getDictionaryObject(key))
                    root.removeItem(key)
                }
            }
            for (i in 0 until kids.size()) (kids.getObject(i) as COSDictionary).setItem(COSName.PARENT, node)
            root.setItem(COSName.KIDS, COSArray().apply { add(node) })
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val bytes = run(source) { input, output -> rearrangePages(context, input, output, "", listOf(2, 0, 1)) }
        PDDocument.load(bytes).use { doc ->
            assertEquals(3, doc.numberOfPages)
            for (page in doc.pages) {
                assertNotNull("font resource", page.resources?.getFont(TestPdfs.INHERITED_FONT))
                assertEquals(TestPdfs.INHERITED_MEDIA_BOX.toString(), page.mediaBox.toString())
                assertEquals(TestPdfs.INHERITED_ROTATION, page.rotation)
            }
        }
    }

    @Test
    fun `split still copies the chosen pages into a new document`() {
        val output = File(context.cacheDir, "split-out.pdf").apply { delete() }
        val input = TestPdfs.write(context.cacheDir, TestPdfs.structured(), "split-in.pdf")
        assertEquals(2, runBlocking { extractPages(context, input, Uri.fromFile(output), "", listOf(3, 0, 9)) })
        PDDocument.load(output).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertNull(doc.documentCatalog.documentOutline)
        }
    }
}
