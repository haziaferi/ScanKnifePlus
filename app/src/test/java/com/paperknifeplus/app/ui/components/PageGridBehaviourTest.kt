package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ui.scan.CaptureMode
import com.haziaferi.scanknifeplus.ui.scan.ReviewActions
import com.haziaferi.scanknifeplus.ui.scan.ReviewPage
import com.haziaferi.scanknifeplus.ui.scan.ReviewScreen
import com.haziaferi.scanknifeplus.ui.scan.ReviewUi
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The page grids' arrows, touch and hold and first-time hint (R3, rule 10): every arrow that would do nothing at the first or last page is
 * disabled (inert, read out as such, and drawn faded); touch and hold on every tile opens its page titled with the file's name, while a tap
 * keeps its own job; and the grids say so once, until the first touch and hold.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Tall, so the whole screen, the line under the grid too, is laid out.
@Config(qualifiers = "w384dp-h2400dp")
class PageGridBehaviourTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun pdf(pages: Int, name: String): Uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(pages), name)

    private fun waitFor(text: String) = rule.waitUntil(60_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun tile(page: Int): SemanticsNodeInteraction = rule.onNode(hasClickAction() and hasContentDescription("Page $page"))

    /** [previous] and [next] are enabled exactly when [canGoBack] and [canGoOn]. */
    private fun assertArrows(previous: String, next: String, canGoBack: Boolean, canGoOn: Boolean) {
        rule.onNodeWithContentDescription(previous).run { if (canGoBack) assertIsEnabled() else assertIsNotEnabled() }
        rule.onNodeWithContentDescription(next).run { if (canGoOn) assertIsEnabled() else assertIsNotEnabled() }
    }

    @Test
    fun `Rearrange's move arrows are disabled at the first and last page and with no page picked, enabled between`() {
        rule.setContent { RearrangeView(initialUri = pdf(3, "arrows-rearrange.pdf"), onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor("Move to…")
        assertArrows("Move back", "Move forward", canGoBack = false, canGoOn = false)
        tile(1).performClick()
        assertArrows("Move back", "Move forward", canGoBack = false, canGoOn = true)
        tile(2).performClick()
        assertArrows("Move back", "Move forward", canGoBack = true, canGoOn = true)
        tile(3).performClick()
        assertArrows("Move back", "Move forward", canGoBack = true, canGoOn = false)
        // A disabled arrow takes no tap: the order stays.
        rule.onNodeWithContentDescription("Move forward").performClick()
        assertArrows("Move back", "Move forward", canGoBack = true, canGoOn = false)
    }

    @Test
    fun `the page viewer's arrows are disabled at the first and last page, enabled between`() {
        val uri = pdf(3, "arrows-viewer.pdf")
        var page by mutableIntStateOf(0)
        rule.setContent { PageViewer(uri, null, page, 3, aspect = 0.707f, onPageChange = { page = it }) }
        assertArrows("Previous page", "Next page", canGoBack = false, canGoOn = true)
        rule.onNodeWithContentDescription("Next page").performClick()
        rule.onNodeWithText("Page 2 of 3").assertExists()
        assertArrows("Previous page", "Next page", canGoBack = true, canGoOn = true)
        rule.onNodeWithContentDescription("Next page").performClick()
        rule.onNodeWithText("Page 3 of 3").assertExists()
        assertArrows("Previous page", "Next page", canGoBack = true, canGoOn = false)
    }

    @Test
    fun `the full-screen page view's arrows are disabled at the first and last page, enabled between`() {
        val uri = pdf(3, "arrows-lightbox.pdf")
        rule.setContent { PageLightbox(uri, initialPage = 0, totalCount = 3, password = null, onDismiss = {}) }
        waitFor("1 / 3")
        assertArrows("Previous page", "Next page", canGoBack = false, canGoOn = true)
        rule.onNodeWithContentDescription("Next page").performClick()
        waitFor("2 / 3")
        assertArrows("Previous page", "Next page", canGoBack = true, canGoOn = true)
        rule.onNodeWithContentDescription("Next page").performClick()
        waitFor("3 / 3")
        assertArrows("Previous page", "Next page", canGoBack = true, canGoOn = false)
    }

    /** How far [c] is from [ground], summed over the colour channels. */
    private fun distance(c: Color, ground: Color) = abs(c.red - ground.red) + abs(c.green - ground.green) + abs(c.blue - ground.blue)

    /** The strongest mark [node] draws: the largest [distance] of any of its pixels from its top-left pixel, its background. */
    private fun strongestMark(node: SemanticsNodeInteraction): Float {
        val pixels = node.captureToImage().toPixelMap()
        val ground = pixels[0, 0]
        var strongest = 0f
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) strongest = maxOf(strongest, distance(pixels[x, y], ground))
        return strongest
    }

    @Test
    fun `Rearrange draws a disabled move arrow faded`() {
        var scheme: ColorScheme? = null
        rule.setContent {
            scheme = MaterialTheme.colorScheme
            RearrangeView(initialUri = pdf(3, "faded-rearrange.pdf"), onBack = {}, onOpenPreview = { _, _, _ -> })
        }
        waitFor("Move to…")
        tile(1).performClick()
        rule.onNodeWithContentDescription("Move back").assertIsNotEnabled()
        val arrow = rule.onNodeWithContentDescription("Move back")
        val ground = arrow.captureToImage().toPixelMap()[0, 0]
        // Faded is the text colour at 38%: well under the full text colour's distance from the bar, which a plain grey would come near.
        val ratio = strongestMark(arrow) / distance(scheme!!.onSurface.compositeOver(ground), ground)
        assertTrue("the disabled arrow is drawn at $ratio of the text colour's strength", ratio < 0.55f)
    }

    @Test
    fun `Review draws a disabled move button's ring faded with its icon`() {
        val pages = (1..3).map { ReviewPage("p$it", File("page$it.jpg")) }
        rule.setContent { PaperKnifePlusTheme { ReviewScreen(ReviewUi(CaptureMode.MULTI, pages, selectedId = "p1"), ReviewActions()) } }
        rule.onNodeWithContentDescription("Move page earlier").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Move page later").assertIsEnabled()
        // The ring crosses the top middle of the button; outside the circle, its top-left pixel is the background.
        fun ring(description: String): Float {
            val pixels = rule.onNodeWithContentDescription(description).captureToImage().toPixelMap()
            val ground = pixels[0, 0]
            return (0 until 4).maxOf { y -> distance(pixels[pixels.width / 2, y], ground) }
        }
        val ratio = ring("Move page earlier") / ring("Move page later")
        assertTrue("the disabled ring is drawn at $ratio of the enabled one's strength", ratio < 0.7f)
    }

    @Test
    fun `a status line with an empty action slot shows its status`() {
        rule.setContent { ToolStatusRow("0 / 3 pages selected") {} }
        rule.onNodeWithText("0 / 3 pages selected").assertIsDisplayed()
    }

    /** The tool [id] with a 3-page file, once its file card shows; a file:// URI has no display name, so the tools call it Document.pdf. */
    private fun showTool(id: String, name: String) {
        val uri = pdf(3, name)
        rule.setContent { ToolCatalog.byId(id)!!.screen(ToolLaunch(uri, null, onBack = {}, onOpenPreview = { _, _, _ -> })) }
        waitFor("Document.pdf")
    }

    /** Touch and hold on page [page]'s tile opens that page full screen, titled with the file's name (R3), and Close shuts it. */
    private fun assertHoldReadsPage(page: Int) {
        tile(page).performTouchInput { longClick() }
        waitFor("Page $page of Document.pdf")
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onAllNodes(hasText("Page $page of Document.pdf")).assertCountEquals(0)
    }

    @Test
    fun `Split's tiles pick a page on tap and read it on touch and hold`() {
        showTool("split", "hold-split.pdf")
        assertHoldReadsPage(2)
        rule.onNodeWithText("0 / 3 pages selected").assertExists()
        tile(1).performClick()
        waitFor("1 / 3 pages selected")
    }

    @Test
    fun `Delete's tiles mark a page on tap and read it on touch and hold`() {
        showTool("delete", "hold-delete.pdf")
        assertHoldReadsPage(2)
        rule.onNodeWithText(STATUS).assertExists()
        tile(1).performClick()
        waitFor("1 / 3 marked for deletion")
    }

    @Test
    fun `Rotate's tiles rotate on tap and read the page on touch and hold`() {
        showTool("rotate", "hold-rotate.pdf")
        assertHoldReadsPage(2)
        rule.onNodeWithText("0 / 3 pages modified").assertExists()
        tile(1).performClick()
        waitFor("1 / 3 pages modified")
    }

    @Test
    fun `Grayscale's tiles show the page on tap and on touch and hold`() {
        showTool("grayscale", "hold-grayscale.pdf")
        assertHoldReadsPage(2)
        tile(1).performClick()
        waitFor("Page 1 of Document.pdf")
    }

    @Test
    fun `Page numbers' tiles do nothing on tap and read the page on touch and hold`() {
        showTool("page-numbers", "hold-numbers.pdf")
        // The tile's number preview stays on the tile: the full-screen view adds no second, tile-sized "Page 2".
        tile(2).performTouchInput { longClick() }
        waitFor("Page 2 of Document.pdf")
        rule.onAllNodes(hasText("Page 2"), useUnmergedTree = true).assertCountEquals(1)
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onAllNodes(hasText("Page 2 of Document.pdf")).assertCountEquals(0)
        tile(1).performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("Page 1 of Document.pdf")).assertCountEquals(0)
    }

    @Test
    fun `Sign's tiles open the signing view on tap and read the page on touch and hold`() {
        showTool("sign", "hold-sign.pdf")
        assertHoldReadsPage(2)
        rule.onAllNodes(hasText("Add sign")).assertCountEquals(0)
        tile(1).performClick()
        waitFor("Add sign")
    }

    @Test
    fun `Watermark's tiles open the watermark view on tap and read the page on touch and hold`() {
        showTool("watermark", "hold-watermark.pdf")
        assertHoldReadsPage(2)
        rule.onAllNodes(hasText("Add watermark")).assertCountEquals(0)
        tile(1).performClick()
        waitFor("Add watermark")
    }

    @Test
    fun `Rearrange's tiles pick a page on tap and read it on touch and hold`() {
        showTool("rearrange", "hold-rearrange.pdf")
        assertHoldReadsPage(2)
        rule.onNodeWithText("Move to…").assertIsNotEnabled()
        tile(1).performClick()
        rule.onNodeWithText("Move to…").assertIsEnabled()
    }

    @Test
    fun `Delete's grid shows the hold hint until a page is touched and held, then never again`() {
        val uri = pdf(3, "hint-delete.pdf")
        var visit by mutableIntStateOf(0)
        rule.setContent {
            key(visit) { DeleteView(initialUri = uri, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        }
        waitFor(STATUS)
        rule.onNodeWithText(HOLD).assertExists()
        assertFalse(PreferencesManager.isPageHoldUsed(context))
        val read = tile(2).fetchSemanticsNode().config[SemanticsActions.OnLongClick]
        assertEquals("TalkBack names the touch and hold", "Read page 2", read.label)

        assertHoldReadsPage(2)
        rule.onNodeWithText(HOLD).assertDoesNotExist()
        assertTrue(PreferencesManager.isPageHoldUsed(context))

        visit++
        rule.waitForIdle()
        waitFor(STATUS)
        rule.onNodeWithText(HOLD).assertDoesNotExist()
    }

    @Test
    fun `Rotate's grid shows the hold hint too, and TalkBack offers the touch and hold`() {
        showTool("rotate", "hint-rotate.pdf")
        rule.onNodeWithText(HOLD).assertExists()
        assertEquals("Read page 1", tile(1).fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
    }

    @Test
    // The 6T at the default text size: the hint shows under the grid without any scrolling.
    @Config(qualifiers = "w384dp-h832dp")
    fun `on the 6T the hold hint is in view as the screen opens`() {
        showTool("delete", "hint-6t.pdf")
        val hint = rule.onNodeWithText(HOLD)
        hint.assertIsDisplayed()
        val window = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = hint.fetchSemanticsNode().boundsInRoot
        assertTrue("the hint ($bounds) is whole in the window ($window)", bounds.top >= window.top && bounds.bottom <= window.bottom && bounds.height > 0f)
        val node = hint.fetchSemanticsNode()
        assertEquals("the hint is not cut", node.size.height.toFloat(), node.boundsInRoot.height, 0.5f)
    }

    private companion object {
        const val HOLD = "Touch and hold a page to read it."
        const val STATUS = "0 / 3 marked for deletion"
    }
}
