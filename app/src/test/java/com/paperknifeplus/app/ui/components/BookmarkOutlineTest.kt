package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import kotlinx.coroutines.runBlocking
import java.io.File
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class BookmarkOutlineTest {
    private fun fit(page: PDPage) = PDPageFitDestination().apply { this.page = page }

    /**
     * Three pages and the outline: "Parent" (page 1) with child "Child" (GoTo action to page 3), "Named" (named destination to page 2),
     * "Numbered" (a page number, 1, instead of a page reference) and an item without /Title pointing at page 1.
     */
    private fun outlined(): ByteArray = PDDocument().use { doc ->
        repeat(3) { doc.addPage(PDPage()) }
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply {
            dests = PDDestinationNameTreeNode().apply { names = mapOf("chapter" to fit(doc.getPage(1))) }
        }
        val outline = PDDocumentOutline()
        doc.documentCatalog.documentOutline = outline
        val parent = PDOutlineItem().apply { title = "Parent"; destination = fit(doc.getPage(0)) }
        outline.addLast(parent)
        parent.addLast(PDOutlineItem().apply { title = "Child"; action = PDActionGoTo().apply { destination = fit(doc.getPage(2)) } })
        parent.openNode()
        outline.addLast(PDOutlineItem().apply { title = "Named"; destination = PDNamedDestination("chapter") })
        outline.addLast(PDOutlineItem().apply { title = "Numbered"; destination = PDPageFitDestination().apply { pageNumber = 1 } })
        outline.addLast(PDOutlineItem().apply { destination = fit(doc.getPage(0)) })
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun saved(doc: PDDocument): PDDocument = PDDocument.load(ByteArrayOutputStream().also { doc.save(it) }.toByteArray())

    private fun items(doc: PDDocument): List<PDOutlineItem> {
        val list = mutableListOf<PDOutlineItem>()
        fun walk(item: PDOutlineItem?) {
            var current = item
            while (current != null) {
                list += current
                walk(current.firstChild)
                current = current.nextSibling
            }
        }
        walk(doc.documentCatalog.documentOutline?.firstChild)
        return list
    }

    @Test
    fun `targets through destinations, actions, names and page numbers resolve`() {
        PDDocument.load(outlined()).use { doc ->
            assertEquals(
                listOf(
                    Bookmark("Parent", 0, 0, 0),
                    Bookmark("Child", 2, 1, 1),
                    Bookmark("Named", 1, 0, 2),
                    Bookmark("Numbered", 1, 0, 3),
                    Bookmark("", 0, 0, 4)
                ),
                readOutline(doc)
            )
        }
    }

    @Test
    fun `saving with an added bookmark keeps every original target, action and the nesting`() {
        PDDocument.load(outlined()).use { doc ->
            applyOutline(doc, readOutline(doc) + Bookmark("Added", 2))
            saved(doc).use { reloaded ->
                assertEquals(
                    listOf("Parent" to 0, "Child" to 1, "Named" to 0, "Numbered" to 0, "" to 0, "Added" to 0),
                    readOutline(reloaded).map { it.title to it.depth }
                )
                assertEquals(listOf(0, 2, 1, 1, 0, 2), readOutline(reloaded).map { it.pageIndex })
                val (parent, child, named, numbered, _) = items(reloaded)
                assertTrue(child.action is PDActionGoTo)
                assertNull(child.destination)
                assertEquals("chapter", (named.destination as PDNamedDestination).namedDestination)
                assertEquals(1, (numbered.destination as PDPageDestination).pageNumber)
                assertTrue(parent.isNodeOpen)
                assertTrue(items(reloaded).last().destination is PDPageFitWidthDestination)
                assertEquals(6, reloaded.documentCatalog.documentOutline.cosObject.getInt(COSName.COUNT))
            }
        }
    }

    @Test
    fun `deleting a parent removes its children`() {
        PDDocument.load(outlined()).use { doc ->
            val edited = readOutline(doc).withoutBranch(0)
            assertEquals(listOf("Named", "Numbered", ""), edited.map { it.title })
            applyOutline(doc, edited)
            saved(doc).use { reloaded ->
                assertEquals(listOf("Named", "Numbered", ""), readOutline(reloaded).map { it.title })
                assertEquals(3, reloaded.documentCatalog.documentOutline.cosObject.getInt(COSName.COUNT))
            }
        }
    }

    @Test
    fun `deleting an item in the middle relinks its siblings`() {
        PDDocument.load(outlined()).use { doc ->
            val list = readOutline(doc)
            applyOutline(doc, list.withoutBranch(list.indexOfFirst { it.title == "Named" }))
            saved(doc).use { reloaded ->
                assertEquals(listOf("Parent", "Child", "Numbered", ""), readOutline(reloaded).map { it.title })
                assertEquals("Numbered", items(reloaded)[2].title)
                assertEquals("Parent", items(reloaded)[2].previousSibling.title)
            }
        }
    }

    @Test
    fun `a target outside the page tree loads unresolved and saving still works`() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            outline.addLast(PDOutlineItem().apply { title = "Orphan"; destination = fit(PDPage()) })
            saved(doc).use { reloaded ->
                val list = readOutline(reloaded)
                assertEquals(listOf(Bookmark("Orphan", null, 0, 0)), list)
                applyOutline(reloaded, list + Bookmark("New", 0))
                saved(reloaded).use { again -> assertEquals(listOf("Orphan", "New"), readOutline(again).map { it.title }) }
            }
        }
    }

    @Test
    fun `cyclic sibling and child chains are read once`() {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            val item = PDOutlineItem().apply { title = "Loop" }
            outline.addLast(item)
            item.cosObject.setItem(COSName.NEXT, item.cosObject)
            item.cosObject.setItem(COSName.FIRST, item.cosObject)
            assertEquals(listOf("Loop"), readOutline(doc).map { it.title })
            applyOutline(doc, readOutline(doc))
        }
    }

    @Test
    fun `an outline that fails to read is reported unreadable, a wrong password is not`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val uri = Uri.fromFile(File(context.cacheDir, "outlined.pdf").apply { writeBytes(outlined()) })
        assertEquals(OutlineLoad.Unreadable, loadOutline(context, uri, null) { throw IllegalStateException("malformed") })
        assertEquals(5, (loadOutline(context, uri, null) as OutlineLoad.Loaded).bookmarks.size)
        val locked = Uri.fromFile(File(context.cacheDir, "locked-outline.pdf").apply { writeBytes(com.paperknifeplus.app.testing.TestPdfs.userPassword()) })
        assertEquals(OutlineLoad.WrongPassword, loadOutline(context, locked, "wrong"))
    }
}
