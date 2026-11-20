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
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.COSObjectable
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDAction
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionLaunch
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionRemoteGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionSubmitForm
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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

    private fun link(action: PDAction?, y: Float) = PDAnnotationLink().apply {
        rectangle = PDRectangle(72f, y, 100f, 20f)
        this.action = action
    }

    private fun script(name: String) = PDActionJavaScript("app.alert('$name')")
    private fun web(marker: String) = PDActionURI().apply { uri = "https://example.org/$marker" }
    private fun app() = PDActionLaunch().apply { cosObject.setString(COSName.F, "calc.exe") }
    private fun otherFile() = PDActionRemoteGoTo().apply { cosObject.setString(COSName.F, "other.pdf") }
    private fun goTo(page: PDPage) = PDActionGoTo().apply { destination = PDPageFitDestination().apply { this.page = page } }
    private fun submit(marker: String) = PDActionSubmitForm().apply { cosObject.setString(COSName.F, "https://example.org/$marker") }

    /** A rendition action: with its own script (/JS) when [withScript], and with a rendition to play (/R) when [play]. */
    private fun rendition(withScript: Boolean, play: Boolean) = COSDictionary().apply {
        setName(COSName.TYPE, "Action")
        setName(COSName.S, "Rendition")
        if (withScript) setString(COSName.JS, "app.alert('rendition')")
        if (play) {
            setInt(COSName.OP, 0)
            setItem(COSName.R, COSDictionary().apply { setName(COSName.TYPE, "Rendition"); setName(COSName.S, "MR") })
        }
    }

    /** [actions] run one after the other, each the /Next of the one before; returns the first. */
    private fun chain(vararg actions: PDAction): PDAction {
        actions.toList().zipWithNext { action, next -> action.cosObject.setItem(COSName.NEXT, next.cosObject) }
        return actions.first()
    }

    /** An additional-actions dictionary (/AA) holding [actions] under their trigger keys. */
    private fun triggers(vararg actions: Pair<String, COSObjectable>) = COSDictionary().apply { for ((key, action) in actions) setItem(key, action) }

    /** A Screen annotation, which plays media, on [page]. */
    private fun screen(page: PDPage): COSDictionary {
        val screen = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, COSName.SCREEN)
            setItem(COSName.RECT, PDRectangle(72f, 300f, 200f, 150f).cosArray)
        }
        page.annotations = page.annotations + PDAnnotation.createAnnotation(screen)
        return screen
    }

    /** The widget of [doc]'s one text field, placed on [page]; the field and the widget share this dictionary. */
    private fun field(doc: PDDocument, page: PDPage): COSDictionary {
        val form = PDAcroForm(doc).also { doc.documentCatalog.acroForm = it }
        val field = PDTextField(form).apply { partialName = "total" }
        form.fields = listOf(field)
        val widget = field.widgets.single().apply {
            rectangle = PDRectangle(72f, 100f, 100f, 20f)
            this.page = page
        }
        page.annotations = page.annotations + widget
        return widget.cosObject
    }

    /**
     * Three pages:
     * - page 1: a web link and a sticky note; a script when the page opens, a link to another file when it closes;
     * - page 2: a link with a plain destination, and a Screen annotation that plays a rendition with its own script when its page opens;
     * - page 3: app and script links, and a text field that calculates with a script and submits the form on mouse-up, and whose widget
     *   runs a script when its page opens.
     *
     * The catalog: two named scripts; an OpenAction that opens a website and an app, goes to page 1, then opens another website and runs a
     * script; on document events, a script on close, a form submission before saving and a rendition that is only a script after printing;
     * a bookmark that opens a website.
     */
    private fun fixture(): ByteArray = PDDocument.load(TestPdfs.plain(3)).use { doc ->
        val pages = doc.pages.toList()
        pages[0].annotations = listOf(
            link(web("LINK-MARKER"), 700f),
            PDAnnotationText().apply { rectangle = PDRectangle(72f, 500f, 20f, 20f); contents = "keep me" }
        )
        pages[0].cosObject.setItem(COSName.AA, triggers("O" to script("page open"), "C" to otherFile()))
        pages[1].annotations = listOf(
            link(null, 700f).apply { destination = PDPageFitDestination().apply { page = pages[0] } }
        )
        screen(pages[1]).setItem(COSName.AA, triggers("PO" to rendition(withScript = true, play = true)))
        pages[2].annotations = listOf(link(app(), 700f), link(script("link"), 650f))
        field(doc, pages[2]).setItem(
            COSName.AA,
            triggers("C" to PDActionJavaScript(CALCULATE), "U" to submit(SUBMIT_MARKER), "PO" to script("widget"))
        )
        val scripts = PDJavascriptNameTreeNode().apply {
            names = mapOf("one" to script("1"), "two" to script("2"))
        }
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { setJavascript(scripts) }
        doc.documentCatalog.openAction = chain(web("OPEN-MARKER"), app(), goTo(pages[0]), web("NEXT-MARKER"), script("open"))
        doc.documentCatalog.cosObject.setItem(
            COSName.AA,
            triggers("WC" to script("close"), "WS" to submit("SUBMIT-ON-SAVE"), "DP" to rendition(withScript = true, play = false))
        )
        doc.documentCatalog.documentOutline = PDDocumentOutline().apply {
            addLast(PDOutlineItem().apply { title = "Website"; action = web(BOOKMARK_MARKER) })
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun run(): Pair<LinkRemoval, File> {
        val input = TestPdfs.write(context.cacheDir, fixture(), "links-in.pdf")
        val output = File(context.cacheDir, "links-out.pdf").apply { delete() }
        val removal = runBlocking { removeLinksPdf(context, input, Uri.fromFile(output), null) }
        return removal to output
    }

    private fun PDDocument.subtypes(page: Int): List<String> = getPage(page).annotations.map { it.subtype }

    @Test
    fun `every link goes, with every script and link that runs on its own, and what a user fires stays`() {
        val (removal, output) = run()
        assertEquals(FIXTURE_REMOVAL, removal)
        PDDocument.load(output).use { doc ->
            assertEquals(listOf("Text"), doc.subtypes(0))
            assertEquals(listOf("Screen"), doc.subtypes(1))
            assertEquals(listOf("Widget"), doc.subtypes(2))
            assertNull(doc.getPage(0).cosObject.getDictionaryObject(COSName.AA))
            fun triggersOf(page: Int) = doc.getPage(page).annotations.single().cosObject.getDictionaryObject(COSName.AA) as COSDictionary
            val played = triggersOf(1).getDictionaryObject("PO") as COSDictionary
            assertEquals("Rendition", played.getNameAsString(COSName.S))
            assertNull(played.getDictionaryObject(COSName.JS))
            assertNotNull(played.getDictionaryObject(COSName.R))
            assertEquals(setOf("C", "U"), triggersOf(2).keySet().map { it.name }.toSet())
            val catalog = doc.documentCatalog.cosObject
            assertNull((catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.getDictionaryObject(COSName.JAVA_SCRIPT))
            assertNull(catalog.getDictionaryObject(COSName.AA))
            val open = catalog.getDictionaryObject(COSName.OPEN_ACTION) as COSDictionary
            assertEquals("GoTo", open.getNameAsString(COSName.S))
            assertNull(open.getDictionaryObject(COSName.NEXT))
            assertEquals("https://example.org/$BOOKMARK_MARKER", (doc.documentCatalog.documentOutline!!.firstChild!!.action as PDActionURI).uri)
        }
        val text = String(output.readBytes(), Charsets.ISO_8859_1)
        for (gone in listOf("LINK-MARKER", "OPEN-MARKER", "NEXT-MARKER", "SUBMIT-ON-SAVE", "app.alert", "calc.exe", "other.pdf")) {
            assertFalse(gone, text.contains(gone))
        }
        for (kept in listOf(BOOKMARK_MARKER, SUBMIT_MARKER, CALCULATE)) assertTrue(kept, text.contains(kept))
    }

    @Test
    fun `the counts before saving are what saving removes, and the file is left as it was`() {
        val bytes = fixture()
        val input = TestPdfs.write(context.cacheDir, bytes, "links-count.pdf")
        assertEquals(FIXTURE_REMOVAL, runBlocking { countLinkRemoval(context, input, null) })
        assertTrue(File(input.path!!).readBytes().contentEquals(bytes))
        val (removal, output) = run()
        assertEquals(FIXTURE_REMOVAL, removal)
        assertEquals(LinkRemoval(0, 0), runBlocking { countLinkRemoval(context, Uri.fromFile(output), null) })
    }

    /** An action put on a trigger that fires on its own: what [removeLinks] counts of it, and whether the trigger keeps what is left of it. */
    private class Kind(val name: String, val counted: LinkRemoval, val stays: Boolean, val make: (PDDocument) -> COSDictionary)

    @Test
    fun `each trigger that fires on its own loses scripts and links of every kind, and keeps other actions`() {
        val kinds = listOf(
            Kind("script", LinkRemoval(0, 1), stays = false) { script("trigger").cosObject },
            Kind("website", LinkRemoval(1, 0), stays = false) { web("TRIGGER").cosObject },
            Kind("app", LinkRemoval(1, 0), stays = false) { app().cosObject },
            Kind("other file", LinkRemoval(1, 0), stays = false) { otherFile().cosObject },
            Kind("form submission", LinkRemoval(1, 0), stays = false) { submit("TRIGGER").cosObject },
            Kind("rendition with a script", LinkRemoval(0, 1), stays = true) { rendition(withScript = true, play = true) },
            Kind("rendition that is only a script", LinkRemoval(0, 1), stays = false) { rendition(withScript = true, play = false) },
            Kind("rendition", LinkRemoval(0, 0), stays = true) { rendition(withScript = false, play = true) },
            Kind("go to", LinkRemoval(0, 0), stays = true) { doc -> goTo(doc.getPage(0)).cosObject }
        )
        // Each trigger as "owner key", with the dictionary that holds the key, made in a one-page file.
        fun attached(owner: COSDictionary) = COSDictionary().also { owner.setItem(COSName.AA, it) }
        val places: List<Pair<String, (PDDocument) -> COSDictionary>> =
            listOf("catalog OpenAction" to { doc: PDDocument -> doc.documentCatalog.cosObject }) +
                listOf("WC", "WS", "DS", "WP", "DP").map { "catalog $it" to { doc: PDDocument -> attached(doc.documentCatalog.cosObject) } } +
                listOf("O", "C").map { "page $it" to { doc: PDDocument -> attached(doc.getPage(0).cosObject) } } +
                listOf("PO", "PC", "PV", "PI").map { "widget $it" to { doc: PDDocument -> attached(field(doc, doc.getPage(0))) } } +
                listOf("PO", "PC", "PV", "PI").map { "screen $it" to { doc: PDDocument -> attached(screen(doc.getPage(0))) } }
        val plain = TestPdfs.plain()
        for ((where, place) in places) for (kind in kinds) {
            PDDocument.load(plain).use { doc ->
                val holder = place(doc)
                val key = where.substringAfter(' ')
                holder.setItem(key, kind.make(doc))
                val what = "${kind.name} at $where"
                assertEquals(what, kind.counted, removeLinks(doc))
                val left = holder.getDictionaryObject(key)
                if (kind.stays) assertNotNull(what, left) else assertNull(what, left)
                assertEquals("$what, a second time", LinkRemoval(0, 0), removeLinks(doc))
            }
        }
    }

    @Test
    fun `an OpenAction that is only a script is removed, a destination stays`() {
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

    @Test
    fun `the actions left run in the same order`() {
        PDDocument.load(TestPdfs.plain(2)).use { doc ->
            val open = script("open").apply {
                cosObject.setItem(COSName.NEXT, COSArray().apply { add(goTo(doc.getPage(0))); add(web("NEXT")); add(goTo(doc.getPage(1))) })
            }
            doc.documentCatalog.openAction = open
            assertEquals(LinkRemoval(1, 1), removeLinks(doc))

            fun target(action: COSDictionary) = (action.getDictionaryObject(COSName.D) as COSArray).getObject(0)
            val first = doc.documentCatalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION) as COSDictionary
            assertSame(doc.getPage(0).cosObject, target(first))
            val next = first.getDictionaryObject(COSName.NEXT) as COSArray
            assertEquals(1, next.size())
            val second = next.getObject(0) as COSDictionary
            assertSame(doc.getPage(1).cosObject, target(second))
            assertNull(second.getDictionaryObject(COSName.NEXT))
        }
    }

    @Test
    fun `a script met twice or in a loop goes all the same, and counts once`() {
        PDDocument.load(TestPdfs.plain()).use { doc ->
            val shared = script("shared")
            doc.documentCatalog.openAction = goTo(doc.getPage(0)).apply {
                cosObject.setItem(COSName.NEXT, COSArray().apply { add(shared); add(shared) })
            }
            doc.documentCatalog.cosObject.setItem(COSName.AA, triggers("WC" to shared))
            val loop = goTo(doc.getPage(0))
            chain(loop, script("loop"), loop)
            doc.getPage(0).cosObject.setItem(COSName.AA, triggers("O" to loop))

            assertEquals(LinkRemoval(0, 2), removeLinks(doc))
            val catalog = doc.documentCatalog.cosObject
            assertNull((catalog.getDictionaryObject(COSName.OPEN_ACTION) as COSDictionary).getDictionaryObject(COSName.NEXT))
            assertNull(catalog.getDictionaryObject(COSName.AA))
            val opened = (doc.getPage(0).cosObject.getDictionaryObject(COSName.AA) as COSDictionary).getDictionaryObject("O") as COSDictionary
            assertEquals("GoTo", opened.getNameAsString(COSName.S))
            assertNull(opened.getDictionaryObject(COSName.NEXT))
        }
    }

    @Test
    fun `a bookmark that shares its action with the OpenAction keeps its whole chain`() {
        PDDocument.load(TestPdfs.plain()).use { doc ->
            val shared = chain(goTo(doc.getPage(0)), script("bookmark"))
            doc.documentCatalog.openAction = shared
            doc.documentCatalog.documentOutline = PDDocumentOutline().apply { addLast(PDOutlineItem().apply { title = "Start"; action = shared }) }

            assertEquals(LinkRemoval(0, 1), removeLinks(doc))
            assertNull((doc.documentCatalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION) as COSDictionary).getDictionaryObject(COSName.NEXT))
            val bookmark = doc.documentCatalog.documentOutline!!.firstChild!!.action!!.cosObject
            assertEquals("JavaScript", (bookmark.getDictionaryObject(COSName.NEXT) as COSDictionary).getNameAsString(COSName.S))
        }
    }

    @Test
    fun `no default author is added`() {
        PreferencesManager.setDefaultAuthor(context, "Default Person")
        val (_, output) = run()
        PDDocument.load(output).use { assertNull(it.documentInformation.author) }
    }

    @Test
    fun `the copy keeps the source's password`() {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(), "links-locked.pdf")
        val kept = File(context.cacheDir, "links-kept.pdf").apply { delete() }
        runBlocking { removeLinksPdf(context, input, Uri.fromFile(kept), TestPdfs.USER_PASSWORD) }
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(kept).close() }
        PDDocument.load(kept, TestPdfs.USER_PASSWORD).use { assertTrue(it.isEncrypted) }
    }

    private companion object {
        const val BOOKMARK_MARKER = "BOOKMARK-STAYS"
        const val SUBMIT_MARKER = "SUBMIT-STAYS"
        const val CALCULATE = "event.value = 'CALCULATED-STAYS'"

        /**
         * What [fixture] loses. Links: 4 link annotations, the OpenAction's two websites and app, page 1's other file, and the form
         * submission before saving. Scripts: 2 named, and the OpenAction's, document close's, page 1's open, the widget's, the Screen
         * rendition's own and the script-only rendition after printing.
         */
        val FIXTURE_REMOVAL = LinkRemoval(links = 9, scripts = 8)
    }
}
