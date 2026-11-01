package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
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
 * Crop pages on its screen: the catalog entry, the frame dragged by a corner and moved by TalkBack, only the range's pages cropped, a
 * range error, the tip, the readable page card and a protected source; plus the page grid's long-file rule.
 */
@RunWith(RobolectricTestRunner::class)
// A tall window, so the whole screen is laid out without scrolling and touches reach every part of it.
@Config(qualifiers = "w400dp-h1400dp")
class CropViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val output = File(context.cacheDir, "crop-view-out.pdf")
    private var suggested: Any? = null

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        SessionManager.clearHistory()
        PreferencesManager.setTipClosed(context, "crop", false)
        output.delete()
    }

    /** A save picker the user picks [output] in; it remembers the suggested name. */
    private val picker = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                suggested = input
                dispatchResult(requestCode, Uri.fromFile(output))
            }
        }
    }

    private fun show(bytes: ByteArray, name: String, password: String? = null): Uri {
        val input = TestPdfs.write(context.cacheDir, bytes, name)
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                CropView(initialUri = input, initialPassword = password, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        return input
    }

    private fun waitFor(text: String) = rule.waitUntil(60_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun edge(name: String): SemanticsNodeInteraction = rule.onNodeWithContentDescription(name)

    /** Where the TalkBack value of edge [name] puts it, as a fraction of the page. */
    private fun edgeValue(name: String): Float = edge(name).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current

    private fun centre(node: SemanticsNodeInteraction): Offset = node.fetchSemanticsNode().boundsInRoot.center

    private fun PDRectangle.edges() = listOf(lowerLeftX, lowerLeftY, upperRightX, upperRightY)

    private fun assertBox(expected: List<Float>, actual: PDRectangle) {
        for ((e, a) in expected.zip(actual.edges())) assertEquals(actual.edges().toString(), e, a, 0.01f)
    }

    @Test
    fun `Crop pages is in the catalog under Edit, right after Links`() {
        val ids = ToolCatalog.tools.map { it.id }
        assertEquals(ids.indexOf("links") + 1, ids.indexOf("crop"))
        val crop = ToolCatalog.byId("crop")!!
        assertEquals(ToolCategory.EDIT, crop.category)
        assertEquals("Crop pages", context.getString(crop.name))
        assertEquals("Trim the page edges", context.getString(crop.description))
        assertEquals(Icons.Outlined.Crop, crop.icon)
    }

    @Test
    fun `dragging a corner moves the frame with the finger, and the saved crop box is where the frame was drawn`() {
        show(TestPdfs.plain(2), "drag.pdf")
        waitFor(READY)
        // The frame starts as the whole page, so its edges' centres span the page.
        val width = centre(edge("Right edge")).x - centre(edge("Left edge")).x
        val height = centre(edge("Bottom edge")).y - centre(edge("Top edge")).y
        assertEquals(612f / 792f, width / height, 0.01f)

        rule.onNodeWithContentDescription("Top-left corner").performTouchInput {
            swipe(center, center + Offset(width * 0.2f, height * 0.1f), durationMillis = 300)
        }
        rule.waitForIdle()
        assertEquals(0.2f, edgeValue("Left edge"), 0.01f)
        assertEquals(0.1f, edgeValue("Top edge"), 0.01f)
        assertEquals(1f, edgeValue("Right edge"), 0f)
        assertEquals(1f, edgeValue("Bottom edge"), 0f)

        // The bottom-right corner moves the other two edges, up and to the left.
        rule.onNodeWithContentDescription("Bottom-right corner").performTouchInput {
            swipe(center, center - Offset(width * 0.3f, height * 0.25f), durationMillis = 300)
        }
        rule.waitForIdle()
        val frame = CropArea.Fractions(edgeValue("Left edge"), edgeValue("Top edge"), edgeValue("Right edge"), edgeValue("Bottom edge"))
        assertEquals(0.7f, frame.right, 0.01f)
        assertEquals(0.75f, frame.bottom, 0.01f)
        assertEquals(0.2f, frame.left, 0.01f)

        rule.onNodeWithText(CROP).performClick()
        waitFor("Crop complete")
        PDDocument.load(output).use { doc ->
            for (page in doc.pages) {
                // A Letter page, 612 x 792, y up: the frame's top is 792 down by top * 792.
                assertBox(listOf(frame.left * 612, (1 - frame.bottom) * 792, frame.right * 612, (1 - frame.top) * 792), page.cropBox)
                assertBox(listOf(0f, 0f, 612f, 792f), page.mediaBox)
            }
        }
    }

    @Test
    fun `TalkBack moves each edge as an adjustable value, kept on the page and apart from the opposite edge`() {
        show(TestPdfs.plain(), "talkback.pdf")
        waitFor(READY)
        edge("Left edge").performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }
        edge("Right edge").performSemanticsAction(SemanticsActions.SetProgress) { it(0.1f) }
        edge("Top edge").performSemanticsAction(SemanticsActions.SetProgress) { it(-1f) }
        rule.waitForIdle()
        assertEquals(0.3f, edgeValue("Left edge"), 0.0001f)
        assertEquals(0.3f + MIN_FRAME, edgeValue("Right edge"), 0.0001f)
        assertEquals(0f, edgeValue("Top edge"), 0f)
        assertEquals(99, edge("Left edge").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
        // The handles are for fingers; TalkBack reaches the edges instead.
        val corner = rule.onNodeWithContentDescription("Top-left corner").fetchSemanticsNode()
        assertTrue(corner.config.contains(SemanticsProperties.HideFromAccessibility))
    }

    @Test
    fun `only the pages of the range are cropped`() {
        val input = show(TestPdfs.plain(5), "ranged.pdf")
        waitFor(READY)
        rule.onNodeWithText("Page range").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("2, 4-5")
        edge("Left edge").performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        rule.onNodeWithText(CROP).assertIsEnabled().performClick()
        waitFor("Crop complete")

        assertEquals(pdfBaseName(getUriDetails(context, input).name) + "-cropped.pdf", suggested)
        PDDocument.load(output).use { doc ->
            val cropped = doc.pages.map { it.cropBox.lowerLeftX > 1f }
            assertEquals(listOf(false, true, false, true, true), cropped)
            assertBox(listOf(153f, 0f, 612f, 792f), doc.getPage(1).cropBox)
        }
        val entry = SessionManager.history.single()
        assertEquals("crop", entry.toolId)
        assertEquals("3 pages cropped", entry.size)
    }

    @Test
    fun `until a corner moves the button is disabled and says to move one`() {
        show(TestPdfs.plain(2), "untouched.pdf")
        waitFor(READY)
        rule.onNodeWithText(MOVE).assertIsNotEnabled().performClick()
        rule.onNodeWithText(CROP).assertDoesNotExist()
        assertNull(suggested)

        edge("Bottom edge").performSemanticsAction(SemanticsActions.SetProgress) { it(0.9f) }
        rule.onNodeWithText(MOVE).assertDoesNotExist()
        rule.onNodeWithText(CROP).assertIsEnabled()
        // Back to the whole page, there is nothing to crop again.
        edge("Bottom edge").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        rule.onNodeWithText(MOVE).assertIsNotEnabled()
    }

    @Test
    fun `the empty range says nothing until the field is edited, and the shown range error names the button first`() {
        show(TestPdfs.plain(5), "order.pdf")
        waitFor(READY)
        rule.onNodeWithText("Page range").performClick()
        // Untouched: the helper, no error, and the untouched frame names the button.
        rule.onNodeWithText("For example 1-3, 5, 8-").assertExists()
        rule.onNodeWithText("No pages given.").assertDoesNotExist()
        rule.onNodeWithText(MOVE).assertIsNotEnabled()

        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("4-2")
        rule.onNodeWithText("“4-2”: the range runs backwards. Write 2-4.").assertExists()
        rule.onNodeWithText(CHECK).assertIsNotEnabled()
        field.performTextReplacement("2")
        rule.onNodeWithText(MOVE).assertIsNotEnabled()
        edge("Left edge").performSemanticsAction(SemanticsActions.SetProgress) { it(0.2f) }
        rule.onNodeWithText(CROP).assertIsEnabled()

        // Emptied after an edit, the field now says so.
        field.performTextReplacement("")
        rule.onNodeWithText("No pages given.").assertExists()
        rule.onNodeWithText(CHECK).assertIsNotEnabled()
    }

    @Test
    fun `an untouched empty range keeps the button disabled without an error`() {
        show(TestPdfs.plain(5), "untyped.pdf")
        waitFor(READY)
        edge("Left edge").performSemanticsAction(SemanticsActions.SetProgress) { it(0.2f) }
        rule.onNodeWithText("Page range").performClick()
        rule.onNodeWithText("No pages given.").assertDoesNotExist()
        rule.onNodeWithText(CHECK).assertIsNotEnabled()
    }

    @Test
    fun `a range error disables the button and says what to fix`() {
        show(TestPdfs.plain(5), "errors.pdf")
        waitFor(READY)
        edge("Left edge").performSemanticsAction(SemanticsActions.SetProgress) { it(0.2f) }
        rule.onNodeWithText("Page range").performClick()

        val field = rule.onNode(hasSetTextAction())
        val messages = mapOf(
            "4-2" to "“4-2”: the range runs backwards. Write 2-4.",
            "9" to "“9”: page 9 doesn’t exist. This file has 5 pages.",
            "x" to "“x” isn’t a page or a range. Use for example 1-3, 5, 8-."
        )
        for ((typed, message) in messages) {
            field.performTextReplacement(typed)
            rule.onNodeWithText(message).assertExists()
            rule.onNodeWithText(CHECK).assertIsNotEnabled().performClick()
            rule.onNodeWithText(CROP).assertDoesNotExist()
        }
        assertNull(suggested)

        field.performTextReplacement("2-4")
        rule.onNodeWithText("For example 1-3, 5, 8-").assertExists()
        rule.onNodeWithText(CROP).assertIsEnabled()
        // All pages needs no range, whatever the field held.
        field.performTextReplacement("4-2")
        rule.onNodeWithText("All pages").performClick()
        rule.onNodeWithText(CROP).assertIsEnabled()
        assertFalse(output.exists())
    }

    @Test
    fun `a closed tip stays closed, and the (i) brings it back`() {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(), "tip.pdf")
        var visit by mutableIntStateOf(0)
        rule.setContent {
            key(visit) { CropView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        }
        waitFor(READY)
        rule.onNodeWithText(TIP).assertExists()
        rule.onNodeWithContentDescription("Show the tip").assertDoesNotExist()
        rule.onNodeWithContentDescription("Close the tip").performClick()
        rule.onNodeWithText(TIP).assertDoesNotExist()

        // A later visit to Crop opens with the tip closed and the (i) in the top bar.
        visit++
        waitFor(READY)
        rule.onNodeWithText(TIP).assertDoesNotExist()
        rule.onNodeWithContentDescription("Show the tip").performClick()
        rule.onNodeWithText(TIP).assertExists()
        rule.onNodeWithContentDescription("Show the tip").assertDoesNotExist()
        assertFalse(PreferencesManager.isTipClosed(context, "crop"))
    }

    @Test
    fun `a page in the grid shows in the viewer on tap, and touch and hold or TalkBack's action opens it to read`() {
        val name = getUriDetails(context, show(TestPdfs.plain(4), "letters.pdf")).name
        waitFor(READY)
        rule.onNodeWithText("Page 1 of 4").assertExists()
        rule.onNodeWithContentDescription("Show page 3").performClick()
        rule.onNodeWithText("Page 3 of 4").assertExists()
        assertTrue(rule.onNodeWithContentDescription("Show page 3").fetchSemanticsNode().config[SemanticsProperties.Selected])

        rule.onNodeWithContentDescription("Show page 2").performTouchInput { longClick() }
        waitFor("Page 2 of $name")
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithText("Page 2 of $name").assertDoesNotExist()

        val action = rule.onNodeWithContentDescription("Show page 4").fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        assertEquals("Read page 4", action.label)
        rule.runOnUiThread { action.action() }
        waitFor("Page 4 of $name")
    }

    @Test
    fun `a protected source keeps its password, and the copy opens only with it`() {
        show(TestPdfs.userPassword(3), "locked.pdf")
        waitFor("Password required")
        rule.onNode(hasSetTextAction()).performTextInput("wrong")
        rule.onNodeWithText("Unlock").performClick()
        waitFor("Incorrect password, try again.")
        rule.onNode(hasSetTextAction()).performTextReplacement(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()
        waitFor(READY)

        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        edge("Top edge").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        rule.onNodeWithText(CROP).performClick()
        waitFor("Crop complete")

        rule.onNodeWithText(PASSWORD_LINE).assertExists()
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(output).close() }
        PDDocument.load(output, TestPdfs.USER_PASSWORD).use { doc ->
            assertTrue(doc.isEncrypted)
            for (page in doc.pages) assertBox(listOf(0f, 0f, 612f, 396f), page.cropBox)
        }
    }

    @Test
    fun `a grid shows every page up to 15, and two rows with Show all for a longer file`() {
        var pages by mutableIntStateOf(GRID_ALL_UP_TO)
        val shown = mutableListOf<Int>()
        rule.setContent {
            key(pages) {
                PageGrid(pages, isSelected = { false }, tapLabel = { "Show page ${it + 1}" }, onTap = { shown += it }, onRead = {}) {
                    Box(Modifier.size(48.dp, 64.dp))
                }
            }
        }
        rule.onNodeWithContentDescription("Show page 15").assertExists()
        rule.onNodeWithText("Show all", substring = true).assertDoesNotExist()

        pages = GRID_ALL_UP_TO + 1
        rule.onNodeWithContentDescription("Show page 10").assertExists()
        rule.onNodeWithContentDescription("Show page 11").assertDoesNotExist()
        rule.onNodeWithText("Show all 16 pages").performClick()
        rule.onNodeWithContentDescription("Show page 16").performClick()
        rule.onNodeWithText("Show all", substring = true).assertDoesNotExist()
        assertEquals(listOf(15), shown)
        // Five a row: page 6 starts the second row under page 1.
        val first = rule.onNodeWithContentDescription("Show page 1").fetchSemanticsNode().boundsInRoot
        val sixth = rule.onNodeWithContentDescription("Show page 6").fetchSemanticsNode().boundsInRoot
        assertEquals(first.left, sixth.left, 0.5f)
        assertTrue(sixth.top >= first.bottom)
    }

    @Test
    fun `at the usual text size the segmented control is one row of radio buttons`() {
        var picked by mutableIntStateOf(0)
        rule.setContent { SegmentedChoice(listOf("All pages", "Page range"), picked, onSelect = { picked = it }) }
        val all = rule.onNodeWithText("All pages").fetchSemanticsNode().boundsInRoot
        val range = rule.onNodeWithText("Page range").fetchSemanticsNode().boundsInRoot
        assertEquals("one row", all.top, range.top, 0.5f)
        assertTrue(range.left >= all.right - 0.5f)
        rule.onNodeWithText("All pages").assertIsSelected()
        rule.onNodeWithText("Page range").assertIsNotSelected().performClick()
        assertEquals(1, picked)
        rule.onNodeWithText("Page range").assertIsSelected()
        assertEquals(Role.RadioButton, rule.onNodeWithText("Page range").fetchSemanticsNode().config[SemanticsProperties.Role])
    }

    private fun assertFrame(expected: List<Float>, actual: CropArea.Fractions) {
        val edges = listOf(actual.left, actual.top, actual.right, actual.bottom)
        for ((e, a) in expected.zip(edges)) assertEquals(edges.toString(), e, a, 1e-5f)
    }

    @Test
    fun `the frame's mapping keeps it on the page and never empty`() {
        val frame = CropArea.Fractions(0.1f, 0.2f, 0.9f, 0.8f)
        assertFrame(listOf(0.15f, 0.3f, 0.9f, 0.8f), frame.moveCorner(FrameCorner.TOP_LEFT, 0.05f, 0.1f))
        assertFrame(listOf(0.1f, 0.1f, 0.8f, 0.8f), frame.moveCorner(FrameCorner.TOP_RIGHT, -0.1f, -0.1f))
        assertFrame(listOf(0.1f, 0.2f, 1f, 0.9f), frame.moveCorner(FrameCorner.BOTTOM_RIGHT, 0.5f, 0.1f))
        assertFrame(listOf(0f, 0.2f, 0.9f, 0.2f + MIN_FRAME), frame.moveCorner(FrameCorner.BOTTOM_LEFT, -1f, -1f))
        assertFrame(listOf(0.9f - MIN_FRAME, 0.2f, 0.9f, 0.8f), frame.withEdge(FrameEdge.LEFT, 0.95f))
        assertEquals(0.8f, frame.edge(FrameEdge.BOTTOM))
    }

    private companion object {
        const val CROP = "Crop and save PDF"
        const val MOVE = "Move a corner to crop"

        /** Shown once a file is open, whatever the button says. */
        const val READY = "The frame on other pages"
        const val CHECK = "Check the page range"
        const val TIP = "Cropping hides the edges; it doesn’t delete them. To remove content for good, use Redact."
        const val PASSWORD_LINE = "Password-protected. The saved copy keeps this password."
    }
}
