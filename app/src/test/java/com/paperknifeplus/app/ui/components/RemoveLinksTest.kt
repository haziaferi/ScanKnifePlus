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

    private fun run(options: LinkOptions = LinkOptions()): Pair<LinkRemoval, File> {
        val input = TestPdfs.write(context.cacheDir, fixture(), "links-in.pdf")
        val output = File(context.cacheDir, "links-out.pdf").apply { delete() }
        val removal = runBlocking { removeLinksPdf(context, input, Uri.fromFile(output), null, options) }
        return removal to output
    }

    /** What [removeLinks] with [options] takes out of this document. */
    private fun PDDocument.remove(options: LinkOptions = LinkOptions()): LinkRemoval = removeLinks(this, options).removal(options)

    private fun PDDocument.subtypes(page: Int): List<String> = getPage(page).annotations.map { it.subtype }

    /** The trigger keys of [owner]'s /AA. */
    private fun triggerKeys(owner: COSDictionary): Set<String> =
        (owner.getDictionaryObject(COSName.AA) as? COSDictionary)?.keySet()?.map { it.name }?.toSet().orEmpty()

    /** The catalog of a saved [fixture] keeps no script and no trigger that runs on its own, and its OpenAction only goes to a page. */
    private fun PDDocument.assertCatalogCleared() {
        val catalog = documentCatalog.cosObject
        assertNull((catalog.getDictionaryObject(COSName.NAMES) as? COSDictionary)?.getDictionaryObject(COSName.JAVA_SCRIPT))
        assertNull(catalog.getDictionaryObject(COSName.AA))
        val open = catalog.getDictionaryObject(COSName.OPEN_ACTION) as COSDictionary
        assertEquals("GoTo", open.getNameAsString(COSName.S))
        assertNull(open.getDictionaryObject(COSName.NEXT))
    }

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
            doc.assertCatalogCleared()
            assertEquals("https://example.org/$BOOKMARK_MARKER", (doc.documentCatalog.documentOutline!!.firstChild!!.action as PDActionURI).uri)
        }
        val text = String(output.readBytes(), Charsets.ISO_8859_1)
        for (gone in listOf("LINK-MARKER", "OPEN-MARKER", "NEXT-MARKER", "SUBMIT-ON-SAVE", "app.alert", "calc.exe", "other.pdf")) {
            assertFalse(gone, text.contains(gone))
        }
        for (kept in listOf(BOOKMARK_MARKER, SUBMIT_MARKER, CALCULATE)) assertTrue(kept, text.contains(kept))
    }

    @Test
    fun `a range takes the link annotations of its pages only`() {
        // Pages 1 and 3 (indices 0 and 2): their three links go, page 2's link to page 1 stays with its destination.
        val (removal, output) = run(LinkOptions(pages = setOf(0, 2)))
        assertEquals(LinkRemoval(links = AUTOMATIC.links + 3, scripts = AUTOMATIC.scripts), removal)
        PDDocument.load(output).use { doc ->
            assertEquals(listOf("Text"), doc.subtypes(0))
            assertEquals(listOf("Link", "Screen"), doc.subtypes(1))
            assertNotNull(doc.getPage(1).annotations.first().cosObject.getDictionaryObject(COSName.DEST))
            assertEquals(listOf("Widget"), doc.subtypes(2))
        }
    }

    @Test
    fun `with a range, what runs on its own still goes from every page`() {
        // Only page 2 (index 1): pages 1 and 3 keep their links, but lose their own triggers and their widget's.
        val (removal, output) = run(LinkOptions(pages = setOf(1)))
        assertEquals(LinkRemoval(links = AUTOMATIC.links + 1, scripts = AUTOMATIC.scripts), removal)
        PDDocument.load(output).use { doc ->
            assertEquals(listOf("Link", "Text"), doc.subtypes(0))
            assertEquals(emptySet<String>(), triggerKeys(doc.getPage(0).cosObject))
            assertEquals(listOf("Screen"), doc.subtypes(1))
            val played = (doc.getPage(1).annotations.single().cosObject.getDictionaryObject(COSName.AA) as COSDictionary).getDictionaryObject("PO")
            assertNull((played as COSDictionary).getDictionaryObject(COSName.JS))
            assertEquals(listOf("Link", "Link", "Widget"), doc.subtypes(2))
            assertEquals(setOf("C", "U"), triggerKeys(doc.getPage(2).annotations.last().cosObject))
            doc.assertCatalogCleared()
        }

        // A link kept outside the range loses the triggers that fire on their own, and keeps its action and what a user fires.
        PDDocument.load(TestPdfs.plain(2)).use { doc ->
            val kept = link(web("KEPT"), 700f).apply { cosObject.setItem(COSName.AA, triggers("PV" to script("shown"), "U" to goTo(doc.getPage(1)))) }
            doc.getPage(0).annotations = listOf(kept)
            assertEquals(LinkRemoval(0, 1), doc.remove(LinkOptions(pages = setOf(1))))
            assertEquals(setOf("U"), triggerKeys(kept.cosObject))
            assertEquals("https://example.org/KEPT", (kept.action as PDActionURI).uri)
        }
    }

    @Test
    fun `a link on two pages stays whole on the page outside the range`() {
        PDDocument.load(TestPdfs.plain(2)).use { doc ->
            val shared = link(web("SHARED"), 700f)
            doc.getPage(0).annotations = listOf(shared)
            doc.getPage(1).annotations = listOf(shared)
            assertEquals(LinkRemoval(1, 0), doc.remove(LinkOptions(pages = setOf(1))))
            assertEquals("https://example.org/SHARED", ((doc.getPage(0).annotations.single() as PDAnnotationLink).action as PDActionURI).uri)
            assertTrue(doc.getPage(1).annotations.isEmpty())
        }
    }

    @Test
    fun `pages that share one annotations array each count and lose only their own links`() {
        /** Two pages whose /Annots is one and the same array, holding a web link and a sticky note. */
        fun sharing() = PDDocument.load(TestPdfs.plain(2)).apply {
            val note = PDAnnotationText().apply { rectangle = PDRectangle(72f, 500f, 20f, 20f) }
            val annots = COSArray().apply { add(link(web("ONE-ARRAY"), 700f)); add(note) }
            pages.forEach { it.cosObject.setItem(COSName.ANNOTS, annots) }
        }
        val found = sharing().use { removeLinks(it) }
        assertEquals(FoundLinks(inside = listOf(0, 0), leaving = listOf(1, 1), automatic = LinkRemoval(0, 0)), found)

        // A range on page 2 only: page 1 keeps its link, with its action.
        sharing().use { doc ->
            val options = LinkOptions(pages = setOf(1))
            assertEquals(LinkRemoval(1, 0), found.removal(options))
            assertEquals(found.removal(options), doc.remove(options))
            assertEquals(listOf("Link", "Text"), doc.subtypes(0))
            assertEquals("https://example.org/ONE-ARRAY", ((doc.getPage(0).annotations.first() as PDAnnotationLink).action as PDActionURI).uri)
            assertEquals(listOf("Text"), doc.subtypes(1))
        }
    }

    /** A link annotation for the kind choice: [name] in its /Contents, and whether it [stays] when only links that leave the file go. */
    private class LinkCase(val name: String, val stays: Boolean, val make: (PDDocument) -> PDAnnotationLink)

    @Test
    fun `when only links that leave the file go, a link stays only if every action of its chain stays inside`() {
        fun action(type: String?) = COSDictionary().apply { setName(COSName.TYPE, "Action"); type?.let { setName(COSName.S, it) } }
        fun named(name: String) = action("Named").apply { setName(COSName.N, name) }
        fun goToPage(doc: PDDocument) = goTo(doc.getPage(1)).cosObject
        /** A link that runs [actions] one after the other, each the /Next of the one before. */
        fun runs(vararg actions: COSDictionary) = link(null, 700f).apply {
            actions.toList().zipWithNext { action, next -> action.setItem(COSName.NEXT, next) }
            cosObject.setItem(COSName.A, actions.first())
        }
        val cases = listOf(
            LinkCase("destination", stays = true) { doc ->
                link(null, 700f).apply { destination = PDPageFitDestination().apply { page = doc.getPage(1) } }
            },
            LinkCase("no action", stays = true) { link(null, 700f) },
            LinkCase("go to", stays = true) { doc -> runs(goToPage(doc)) },
            LinkCase("next page", stays = true) { runs(named("NextPage")) },
            LinkCase("go to, then last page", stays = true) { doc -> runs(goToPage(doc), named("LastPage")) },
            LinkCase("go to, then a website", stays = false) { doc -> runs(goToPage(doc), web("NEXT").cosObject) },
            LinkCase("go to, then a script among its next", stays = false) { doc ->
                runs(goToPage(doc).apply { setItem(COSName.NEXT, COSArray().apply { add(named("PrevPage")); add(script("next")) }) })
            },
            LinkCase("go to, and a website on mouse-up", stays = false) { doc ->
                runs(goToPage(doc)).apply { cosObject.setItem(COSName.AA, triggers("U" to web("UP"))) }
            },
            LinkCase("website", stays = false) { link(web("LINK"), 700f) },
            LinkCase("app", stays = false) { link(app(), 700f) },
            LinkCase("other file", stays = false) { link(otherFile(), 700f) },
            LinkCase("embedded file", stays = false) { runs(action("GoToE")) },
            LinkCase("form submission", stays = false) { link(submit("LINK"), 700f) },
            LinkCase("form data import", stays = false) { runs(action("ImportData")) },
            LinkCase("script", stays = false) { link(script("link"), 700f) },
            LinkCase("hide", stays = false) { runs(action("Hide")) },
            LinkCase("unknown", stays = false) { runs(action("Teleport")) },
            LinkCase("no type", stays = false) { runs(action(null)) }
        )
        val stay = cases.filter { it.stays }.map { it.name }
        val go = cases.size - stay.size
        PDDocument.load(TestPdfs.plain(2)).use { doc ->
            doc.getPage(0).annotations = cases.map { case -> case.make(doc).apply { contents = case.name } }
            val options = LinkOptions(keepInside = true)
            val found = removeLinks(doc, options)
            assertEquals(FoundLinks(inside = listOf(stay.size, 0), leaving = listOf(go, 0), automatic = LinkRemoval(0, 0)), found)
            assertEquals(LinkRemoval(go, 0), found.removal(options))
            assertEquals(stay, doc.getPage(0).annotations.map { it.contents })
            // What stays keeps its action.
            val kept = doc.getPage(0).annotations.single { it.contents == "go to" }.cosObject.getDictionaryObject(COSName.A) as COSDictionary
            assertEquals("GoTo", kept.getNameAsString(COSName.S))

            assertEquals(LinkRemoval(stay.size, 0), doc.remove())
            assertTrue(doc.getPage(0).annotations.isEmpty())
        }
    }

    @Test
    fun `the counts before saving are what saving removes for every choice, and the file is left as it was`() {
        val bytes = fixture()
        val input = TestPdfs.write(context.cacheDir, bytes, "links-count.pdf")
        val found = runBlocking { findLinks(context, input, null) }
        assertTrue(File(input.path!!).readBytes().contentEquals(bytes))
        assertEquals(FoundLinks(inside = listOf(0, 1, 0), leaving = listOf(1, 0, 2), automatic = AUTOMATIC), found)

        val choices = mapOf(
            LinkOptions() to 4,
            LinkOptions(keepInside = true) to 3,
            LinkOptions(pages = setOf(1)) to 1,
            LinkOptions(pages = setOf(1), keepInside = true) to 0,
            LinkOptions(pages = setOf(0, 2)) to 3,
            LinkOptions(pages = setOf(0, 2), keepInside = true) to 3,
            LinkOptions(pages = emptySet()) to 0
        )
        val output = File(context.cacheDir, "links-choice.pdf")
        for ((options, annotations) in choices) {
            val expected = AUTOMATIC.copy(links = AUTOMATIC.links + annotations)
            assertEquals("$options counted", expected, found.removal(options))
            output.delete()
            assertEquals("$options saved", expected, runBlocking { removeLinksPdf(context, input, Uri.fromFile(output), null, options) })
            // The saved file holds what was found less what was removed, and nothing that runs on its own.
            val left = runBlocking { findLinks(context, Uri.fromFile(output), null) }
            assertEquals("$options left", LinkRemoval(found.removal(LinkOptions()).links - expected.links, 0), left.removal(LinkOptions()))
        }
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
                assertEquals(what, kind.counted, doc.remove())
                val left = holder.getDictionaryObject(key)
                if (kind.stays) assertNotNull(what, left) else assertNull(what, left)
                assertEquals("$what, a second time", LinkRemoval(0, 0), doc.remove())
            }
        }
    }

    @Test
    fun `an OpenAction that is only a script is removed, a destination stays`() {
        PDDocument.load(TestPdfs.plain()).use { doc ->
            doc.documentCatalog.openAction = PDActionJavaScript("app.alert(0)")
            assertEquals(LinkRemoval(0, 1), doc.remove())
            assertNull(doc.documentCatalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION))
        }
        PDDocument.load(TestPdfs.plain()).use { doc ->
            val destination = COSArray().apply { add(doc.getPage(0).cosObject); add(COSName.getPDFName("Fit")) }
            doc.documentCatalog.cosObject.setItem(COSName.OPEN_ACTION, destination)
            assertEquals(LinkRemoval(0, 0), doc.remove())
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
            assertEquals(LinkRemoval(1, 1), doc.remove())

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

            assertEquals(LinkRemoval(0, 2), doc.remove())
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

            assertEquals(LinkRemoval(0, 1), doc.remove())
            assertNull((doc.documentCatalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION) as COSDictionary).getDictionaryObject(COSName.NEXT))
            val bookmark = doc.documentCatalog.documentOutline!!.firstChild!!.action!!.cosObject
            assertEquals("JavaScript", (bookmark.getDictionaryObject(COSName.NEXT) as COSDictionary).getNameAsString(COSName.S))
        }
    }

    @Test
    fun `the copy gets the default author, like the other edit tools`() {
        PreferencesManager.setDefaultAuthor(context, "Default Person")
        try {
            val (_, output) = run()
            PDDocument.load(output).use { assertEquals("Default Person", it.documentInformation.author) }
        } finally {
            PreferencesManager.setDefaultAuthor(context, "")
        }
    }

    @Test
    fun `the copy keeps the source's password`() {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(), "links-locked.pdf")
        val kept = File(context.cacheDir, "links-kept.pdf").apply { delete() }
        runBlocking { removeLinksPdf(context, input, Uri.fromFile(kept), TestPdfs.USER_PASSWORD, LinkOptions()) }
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(kept).close() }
        PDDocument.load(kept, TestPdfs.USER_PASSWORD).use { assertTrue(it.isEncrypted) }
    }

    private companion object {
        const val BOOKMARK_MARKER = "BOOKMARK-STAYS"
        const val SUBMIT_MARKER = "SUBMIT-STAYS"
        const val CALCULATE = "event.value = 'CALCULATED-STAYS'"

        /**
         * What runs on its own in [fixture], which goes whatever the options. Links: the OpenAction's two websites and app, page 1's
         * other file, and the form submission before saving. Scripts: 2 named, and the OpenAction's, document close's, page 1's open, the
         * widget's, the Screen rendition's own and the script-only rendition after printing.
         */
        val AUTOMATIC = LinkRemoval(links = 5, scripts = 8)

        /** What [fixture] loses with every link: its 4 link annotations too. */
        val FIXTURE_REMOVAL = LinkRemoval(links = AUTOMATIC.links + 4, scripts = AUTOMATIC.scripts)
    }
}
