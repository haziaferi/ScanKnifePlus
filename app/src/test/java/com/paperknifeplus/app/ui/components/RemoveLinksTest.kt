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
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDJavascriptNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionLaunch
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RemoveLinksTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun link(action: com.tom_roush.pdfbox.pdmodel.interactive.action.PDAction?, y: Float) = PDAnnotationLink().apply {
        rectangle = PDRectangle(72f, y, 100f, 20f)
        this.action = action
    }

    /**
     * Three pages: a URI link and a sticky note on page 1, a link with a plain destination on page 2, Launch and JavaScript links on page 3;
     * two named scripts, an OpenAction going to page 1 then running a script, and a script on the document-close event.
     */
    private fun fixture(): ByteArray = PDDocument.load(TestPdfs.plain(3)).use { doc ->
        val pages = doc.pages.toList()
        pages[0].annotations = listOf(
            link(PDActionURI().apply { uri = "https://tracker.example/LINK-MARKER" }, 700f),
            PDAnnotationText().apply { rectangle = PDRectangle(72f, 500f, 20f, 20f); contents = "keep me" }
        )
        pages[1].annotations = listOf(
            link(null, 700f).apply { destination = PDPageFitDestination().apply { page = pages[0] } }
        )
        pages[2].annotations = listOf(
            link(PDActionLaunch().apply { cosObject.setString(COSName.F, "calc.exe") }, 700f),
            link(PDActionJavaScript("app.alert('link')"), 650f)
        )
        val scripts = PDJavascriptNameTreeNode().apply {
            names = mapOf("one" to PDActionJavaScript("app.alert(1)"), "two" to PDActionJavaScript("app.alert(2)"))
        }
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { setJavascript(scripts) }
        val goTo = PDActionGoTo().apply { destination = PDPageFitDestination().apply { page = pages[0] } }
        goTo.cosObject.setItem(COSName.NEXT, PDActionJavaScript("app.alert('open')").cosObject)
        doc.documentCatalog.openAction = goTo
        doc.documentCatalog.cosObject.setItem(COSName.AA, COSDictionary().apply {
            setItem(COSName.getPDFName("WC"), PDActionJavaScript("app.alert('close')").cosObject)
        })
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun run(pages: Collection<Int>?): Pair<LinkRemoval, File> {
        val input = TestPdfs.write(context.cacheDir, fixture(), "links-in.pdf")
        val output = File(context.cacheDir, "links-out.pdf").apply { delete() }
        val removal = runBlocking { removeLinksPdf(context, input, Uri.fromFile(output), null, pages) }
        return removal to output
    }

    private fun PDDocument.subtypes(page: Int): List<String> = getPage(page).annotations.map { it.subtype }

    @Test
    fun `every link goes from every page, with the document scripts, and other annotations stay`() {
        val (removal, output) = run(null)
        assertEquals(LinkRemoval(links = 4, scripts = 4), removal)
        PDDocument.load(output).use { doc ->
            assertEquals(listOf("Text"), doc.subtypes(0))
            assertTrue(doc.subtypes(1).isEmpty())
            assertTrue(doc.subtypes(2).isEmpty())
            val catalog = doc.documentCatalog.cosObject
            assertNull((catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.getDictionaryObject(COSName.getPDFName("JavaScript")))
            assertNull(catalog.getDictionaryObject(COSName.AA))
            val open = catalog.getDictionaryObject(COSName.OPEN_ACTION) as COSDictionary
            assertEquals("GoTo", open.getNameAsString(COSName.S))
            assertNull(open.getDictionaryObject(COSName.NEXT))
        }
        val text = String(output.readBytes(), Charsets.ISO_8859_1)
        assertFalse(text.contains("LINK-MARKER"))
        assertFalse(text.contains("app.alert"))
        assertFalse(text.contains("calc.exe"))
    }

    @Test
    fun `selected pages lose only their links, while document scripts always go`() {
        val (removal, output) = run(listOf(2))
        assertEquals(LinkRemoval(links = 2, scripts = 4), removal)
        PDDocument.load(output).use { doc ->
            assertEquals(listOf("Link", "Text"), doc.subtypes(0))
            assertEquals(listOf("Link"), doc.subtypes(1))
            assertTrue(doc.subtypes(2).isEmpty())
        }
    }

    @Test
    fun `an OpenAction that is only a script is removed, one that is no script stays`() {
        PDDocument.load(TestPdfs.plain()).use { doc ->
            doc.documentCatalog.openAction = PDActionJavaScript("app.alert(0)")
            assertEquals(LinkRemoval(0, 1), removeLinks(doc))
            assertNull(doc.documentCatalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION))
        }
        PDDocument.load(TestPdfs.plain()).use { doc ->
            val destination = COSArray().apply { add(doc.getPage(0).cosObject); add(COSName.getPDFName("Fit")) }
            doc.documentCatalog.cosObject.setItem(COSName.OPEN_ACTION, destination)
            assertEquals(LinkRemoval(0, 0), removeLinks(doc))
            assertEquals(2, (doc.documentCatalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION) as COSArray).size())
        }
    }
}
