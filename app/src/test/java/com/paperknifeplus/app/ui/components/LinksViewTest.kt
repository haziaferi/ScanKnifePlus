package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Links, Remove on its screen: the counts before saving, files with only a script or only links, a file with nothing to remove, a protected
 * file, Done's report rows, and the Pages and link-kind choices: what they count and save, the range's states, and a new file's start.
 */
@RunWith(RobolectricTestRunner::class)
// A tall window, so the whole screen is laid out without scrolling and touches reach every part of it.
@Config(qualifiers = "w400dp-h1400dp")
class LinksViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val output = File(context.cacheDir, "links-view-out.pdf")
    private var suggested: Any? = null

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        SessionManager.clearHistory()
        output.delete()
    }

    /** The file Choose PDF picks. */
    private var next: Uri? = null

    /** A file picker that picks [next], and a save picker the user picks [output] in, which remembers the suggested name. */
    private val picker = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                if (contract is ActivityResultContracts.GetContent) {
                    dispatchResult(requestCode, next)
                } else {
                    suggested = input
                    dispatchResult(requestCode, Uri.fromFile(output))
                }
            }
        }
    }

    private fun show(bytes: ByteArray, name: String, password: String? = null): Uri {
        val input = TestPdfs.write(context.cacheDir, bytes, name)
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                LinksView(initialUri = input, initialPassword = password, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        return input
    }

    private fun waitFor(text: String) = rule.waitUntil(60_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    /** The node read as [label] with [count]: a count tile before saving, a report row on Done. */
    private fun counted(label: String, count: Int): SemanticsNodeInteraction = rule.onNode(hasText(label) and hasText("$count"))

    @Test
    fun `the counts show before saving, and nothing is written until the button`() {
        show(TestPdfs.linksAndScript(), "counted.pdf")
        waitFor(REMOVE)

        counted("links", TestPdfs.SCRIPTED_LINKS).assertExists()
        counted("script that runs on its own", 1).assertExists()
        rule.onNodeWithText("Nothing changes until you save. Form fields, comments and bookmarks stay.").assertExists()
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
        assertNull(suggested)
        assertFalse(output.exists())
        assertTrue(SessionManager.history.isEmpty())
    }

    /** [TestPdfs.linksAndScript] with only its links, or only its script. */
    private fun only(links: Boolean): ByteArray = PDDocument.load(TestPdfs.linksAndScript()).use { doc ->
        if (links) doc.documentCatalog.cosObject.removeItem(COSName.OPEN_ACTION) else doc.pages.forEach { it.annotations = emptyList() }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun `a file with only a script shows its counts and can be saved`() {
        show(only(links = false), "script-only.pdf")
        waitFor(FOUND)

        counted("links", 0).assertExists()
        counted("script that runs on its own", 1).assertExists()
        rule.onNodeWithText(REMOVE).assertIsEnabled()
    }

    @Test
    fun `a file with only links shows its counts and can be saved`() {
        show(only(links = true), "links-only.pdf")
        waitFor(FOUND)

        counted("links", TestPdfs.SCRIPTED_LINKS).assertExists()
        counted("scripts that run on their own", 0).assertExists()
        rule.onNodeWithText(REMOVE).assertIsEnabled()
    }

    @Test
    fun `Done's report rows are what the engine removed`() {
        val bytes = TestPdfs.linksAndScript()
        val expected = PDDocument.load(bytes).use { removeLinks(it).removal(LinkOptions()) }
        val input = show(bytes, "reported.pdf")
        waitFor(REMOVE)
        rule.onNodeWithText(REMOVE).performClick()
        waitFor(DONE)

        assertEquals(pdfBaseName(getUriDetails(context, input).name) + "-no-links.pdf", suggested)
        counted("Links", expected.links).assertExists()
        counted("Scripts that run on their own", expected.scripts).assertExists()
        rule.onNodeWithText("2 pages").assertExists()
        PDDocument.load(output).use { assertTrue(removeLinks(it).isEmpty) }
        assertEquals("links", SessionManager.history.single().toolId)
    }

    @Test
    fun `a page range narrows the link counts and the saved links, and the script goes from the whole file`() {
        show(TestPdfs.linksAndScript(), "ranged.pdf")
        waitFor(REMOVE)
        rule.onNodeWithText(WHOLE_FILE).assertDoesNotExist()
        rule.onNodeWithText("Page range").performClick()
        rule.onNodeWithText(FOUND_ON_PAGES).assertExists()
        rule.onNodeWithText(WHOLE_FILE).assertExists()
        // No pages typed yet: only what runs on its own.
        counted("links", 0).assertExists()
        rule.onNode(hasSetTextAction()).performTextInput("2")
        counted("link", 1).assertExists()
        counted("script that runs on its own", 1).assertExists()

        // All pages again: every link, and no range line.
        rule.onNodeWithText("All pages").performClick()
        rule.onNodeWithText(FOUND).assertExists()
        rule.onNodeWithText(WHOLE_FILE).assertDoesNotExist()
        counted("links", TestPdfs.SCRIPTED_LINKS).assertExists()

        rule.onNodeWithText("Page range").performClick()
        rule.onNodeWithText(REMOVE).performClick()
        waitFor(DONE)
        counted("Links", 1).assertExists()
        counted("Scripts that run on their own", 1).assertExists()
        PDDocument.load(output).use { doc ->
            assertEquals(2, doc.getPage(0).annotations.size)
            assertTrue(doc.getPage(1).annotations.isEmpty())
            assertNull(doc.documentCatalog.cosObject.getDictionaryObject(COSName.OPEN_ACTION))
        }
    }

    @Test
    fun `an untouched range asks for the pages, and an edited one names its error`() {
        show(TestPdfs.linksAndScript(), "untouched.pdf")
        waitFor(REMOVE)
        rule.onNodeWithText("Page range").performClick()
        rule.onNodeWithText("For example 1-3, 5, 8-").assertExists()
        rule.onNodeWithText("No pages given.").assertDoesNotExist()
        rule.onNodeWithText(TYPE).assertIsNotEnabled().performClick()
        assertNull(suggested)

        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("3")
        rule.onNodeWithText("“3”: page 3 doesn’t exist. This file has 2 pages.").assertExists()
        rule.onNodeWithText(CHECK).assertIsNotEnabled()
        field.performTextReplacement("")
        rule.onNodeWithText("No pages given.").assertExists()
        rule.onNodeWithText(CHECK).assertIsNotEnabled()
        field.performTextReplacement("1-2")
        rule.onNodeWithText(REMOVE).assertIsEnabled()
        assertFalse(output.exists())
    }

    /** [TestPdfs.linksAndScript] with two more links on page 2 that stay inside the file: a GoTo and a plain destination, both to page 1. */
    private fun withInsideLinks(): ByteArray = PDDocument.load(TestPdfs.linksAndScript()).use { doc ->
        fun toFirst() = PDPageFitDestination().apply { page = doc.getPage(0) }
        val second = doc.getPage(1)
        second.annotations = second.annotations + listOf(
            PDAnnotationLink().apply { rectangle = PDRectangle(72f, 600f, 100f, 20f); action = PDActionGoTo().apply { destination = toFirst() } },
            PDAnnotationLink().apply { rectangle = PDRectangle(72f, 550f, 100f, 20f); destination = toFirst() }
        )
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun `External links counts and removes only the links that leave the file`() {
        show(withInsideLinks(), "inside.pdf")
        waitFor(REMOVE)
        counted("links", TestPdfs.SCRIPTED_LINKS + 2).assertExists()
        rule.onNodeWithText(EXTERNAL_NOTE).assertDoesNotExist()

        rule.onNodeWithText("External links").performClick()
        counted("external links", TestPdfs.SCRIPTED_LINKS).assertExists()
        counted("script that runs on its own", 1).assertExists()
        rule.onNodeWithText(EXTERNAL_NOTE).assertExists()
        rule.onNodeWithText(REMOVE).performClick()
        waitFor(DONE)

        counted("Links", TestPdfs.SCRIPTED_LINKS).assertExists()
        PDDocument.load(output).use { doc ->
            assertTrue(doc.getPage(0).annotations.isEmpty())
            assertEquals(2, doc.getPage(1).annotations.size)
            assertEquals(FoundLinks(inside = listOf(0, 2), leaving = listOf(0, 0), automatic = LinkRemoval(0, 0)), removeLinks(doc))
        }
    }

    @Test
    fun `choices that leave nothing to remove disable the button and save nothing`() {
        // Three links on page 1, all to pages of the file, and no script.
        show(TestPdfs.linked(3), "inside-only.pdf")
        waitFor(REMOVE)
        counted("links", 3).assertExists()

        rule.onNodeWithText("External links").performClick()
        counted("external links", 0).assertExists()
        rule.onNodeWithText(NONE_BODY).assertExists()
        rule.onNodeWithText(NOTHING).assertIsNotEnabled().performClick()

        rule.onNodeWithText("All links").performClick()
        rule.onNodeWithText(REMOVE).assertIsEnabled()
        rule.onNodeWithText("Page range").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("2-3")
        counted("links", 0).assertExists()
        rule.onNodeWithText(NOTHING).assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertNull(suggested)
        assertFalse(output.exists())
    }

    @Test
    fun `a new file starts with All pages, All links and an untouched empty range`() {
        show(TestPdfs.linksAndScript(), "first.pdf")
        waitFor(REMOVE)
        rule.onNodeWithText("Page range").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("1")
        rule.onNodeWithText("External links").performClick()

        next = TestPdfs.write(context.cacheDir, TestPdfs.linksAndScript(), "second.pdf")
        rule.onNodeWithText("Change").performClick()
        rule.onNodeWithText("Choose PDF").performClick()
        waitFor(REMOVE)
        rule.onNodeWithText("All pages").assertIsSelected()
        rule.onNodeWithText("All links").assertIsSelected()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onNodeWithText("Page range").performClick()
        assertEquals("", rule.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        rule.onNodeWithText("No pages given.").assertDoesNotExist()
    }

    @Test
    fun `a file with nothing to remove says so, saves nothing and drops the password line`() {
        show(TestPdfs.userPassword(), "nothing.pdf", TestPdfs.USER_PASSWORD)
        waitFor("No links or scripts in this file")

        rule.onNodeWithText(NONE_BODY).assertExists()
        rule.onNodeWithText(REMOVE).assertDoesNotExist()
        rule.onNodeWithText(PASSWORD_LINE).assertDoesNotExist()
        // With nothing in the file there is nothing to choose.
        rule.onNodeWithText("Page range").assertDoesNotExist()
        rule.onNodeWithText("External links").assertDoesNotExist()
        rule.onNodeWithText("Nothing to remove").assertIsNotEnabled().performClick()
        rule.waitForIdle()
        assertNull(suggested)
        assertFalse(output.exists())
        assertTrue(SessionManager.history.isEmpty())
    }

    @Test
    fun `a protected file asks for its password, says the copy keeps it, and the copy opens only with it`() {
        show(TestPdfs.linksAndScript(TestPdfs.USER_PASSWORD), "locked.pdf")
        waitFor("Password required")
        rule.onNode(hasSetTextAction()).performTextInput("wrong")
        rule.onNodeWithText("Unlock").performClick()
        waitFor("Incorrect password, try again.")
        rule.onNode(hasSetTextAction()).performTextReplacement(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()
        waitFor(REMOVE)

        counted("links", TestPdfs.SCRIPTED_LINKS).assertExists()
        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        rule.onNodeWithText(REMOVE).performClick()
        waitFor(DONE)

        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(output).close() }
        PDDocument.load(output, TestPdfs.USER_PASSWORD).use {
            assertTrue(it.isEncrypted)
            assertTrue(removeLinks(it).isEmpty)
        }
    }

    private companion object {
        const val REMOVE = "Remove links and save PDF"
        const val NOTHING = "Nothing to remove"
        const val TYPE = "Type the pages to remove links from"
        const val CHECK = "Check the page range"
        const val DONE = "Link removal complete"
        const val FOUND = "Found in this file"
        const val FOUND_ON_PAGES = "Found on these pages"
        const val WHOLE_FILE = "Scripts and actions that run on their own go from the whole file."
        const val EXTERNAL_NOTE = "Links to pages of this file stay, such as a table of contents."
        const val NONE_BODY = "There is nothing to remove, so nothing is saved."
        const val PASSWORD_LINE = "Password-protected. The saved copy keeps this password."
    }
}
