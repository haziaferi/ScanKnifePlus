package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.testing.drawnTop
import com.paperknifeplus.app.ui.theme.AppPalette
import com.paperknifeplus.app.ui.theme.TargetStep
import com.paperknifeplus.app.ui.theme.Typography
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One member of each R14 step is drawn at its step's height and answers touches across its step's hit area. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TargetStepsTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun px(dp: Dp) = with(rule.density) { dp.toPx() }

    private fun SemanticsNodeInteraction.node(): SemanticsNode = fetchSemanticsNode()

    /** [node] is drawn [step]'s height and its hit area is [step]'s, both at least [step]'s width too. */
    private fun assertStep(what: String, node: SemanticsNode, step: TargetStep) {
        assertEquals("$what is drawn ${step.drawn}", px(step.drawn), node.boundsInRoot.height, 0.5f)
        assertEquals("$what answers across ${step.hit}", px(step.hit), node.touchBoundsInRoot.height, 0.5f)
        assertTrue("$what is at least ${step.hit} wide to the touch", node.touchBoundsInRoot.width >= px(step.hit) - 0.5f)
    }

    @Test
    fun `T1 the bottom primary button`() {
        rule.setContent { ToolPrimaryButton("Save", onClick = {}) }
        assertStep("the primary button", rule.onNode(hasText("Save") and hasClickAction()).node(), TargetStep.COMMIT)
    }

    @Test
    fun `T2 a top-bar icon and a segment`() {
        rule.setContent {
            Column {
                ToolTopBar(title = "Compress", subtitle = null, showChange = false, onBack = {})
                SegmentedChoice(listOf("All pages", "Page range"), 0, onSelect = {})
            }
        }
        assertStep("Back", rule.onNode(hasContentDescription("Back") and hasClickAction()).node(), TargetStep.ACT)
        assertStep("a segment", rule.onNode(hasText("Page range") and hasClickAction()).node(), TargetStep.ACT)
    }

    /** The bar shown sits on one line, as before stacking existed: Back ([back]) and each of [actions] 48 dp high and centred alike. */
    private fun assertOneLineBar(where: String, back: String, actions: List<String>) {
        val backBounds = rule.onNode(hasContentDescription(back) and hasClickAction()).node().boundsInRoot
        assertEquals("$where: Back is 48 dp", px(48.dp), backBounds.height, 0.5f)
        for (action in actions) {
            val bounds = rule.onNode((hasText(action) or hasContentDescription(action)) and hasClickAction()).node().boundsInRoot
            assertEquals("$where: $action is 48 dp", px(48.dp), bounds.height, 0.5f)
            assertEquals("$where: $action is on Back's line", backBounds.center.y, bounds.center.y, 0.5f)
        }
    }

    @Test
    @Config(qualifiers = "w384dp-h832dp")
    fun `on the 6T Merge's bar is one line`() {
        rule.setContent { MaterialTheme(typography = Typography) { ToolTopBar(title = "Merge", subtitle = "Combine PDFs", showChange = true, onBack = {}) } }
        assertOneLineBar("Merge", "Back", listOf("Change"))
    }

    @Test
    @Config(qualifiers = "w384dp-h832dp")
    fun `on the 6T Compare's bar is one line`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "steps-compare.pdf")
        // With a file picked the bar offers Reset, as the app shows it.
        rule.setContent { MaterialTheme(typography = Typography) { CompareView(initialUri = uri, onBack = {}, onOpenPreview = { _, _, _ -> }) } }
        rule.waitUntil("Reset shows", 60_000) { rule.onAllNodes(hasText("Reset") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        assertOneLineBar("Compare", "Back", listOf("Reset"))
    }

    @Test
    @Config(qualifiers = "w384dp-h832dp")
    fun `on the 6T a bar with Change and the (i) is one line`() {
        // No screen shows both today; the bar allows it, as Crop shows the (i) and Merge shows Change.
        rule.setContent {
            MaterialTheme(typography = Typography) { ToolTopBar(title = "Merge", subtitle = "Combine PDFs", showChange = true, onBack = {}, onShowTip = {}) }
        }
        assertOneLineBar("Merge with the (i)", "Back", listOf("Change", "Show the tip"))
    }

    @Test
    @Config(qualifiers = "w384dp-h832dp")
    fun `on the 6T Make searchable's bar is one line`() {
        rule.setContent { MaterialTheme(typography = Typography) { SearchableView(onBack = {}, onOpenPreview = { _, _, _ -> }) } }
        assertOneLineBar("Make searchable", "Back", listOf("Choose images"))
    }

    @Test
    @Config(qualifiers = "w384dp-h832dp")
    fun `on the 6T Settings' Back is 48 dp`() {
        rule.setContent {
            MaterialTheme(typography = Typography) {
                SettingsView(onThemeChange = {}, palette = AppPalette.STILL_WATER, onPaletteChange = {}, onOpenAbout = {}, onBack = {})
            }
        }
        assertOneLineBar("Settings", "Back to Home", emptyList())
    }

    @Test
    @Config(qualifiers = "w384dp-h832dp")
    fun `on the 6T About's Back is 48 dp`() {
        rule.setContent { MaterialTheme(typography = Typography) { AboutView(onBack = {}, loadNotices = { emptyList() }) } }
        assertOneLineBar("About", "Back", emptyList())
    }

    @Test
    fun `T3 the file card's Change`() {
        rule.setContent { ToolFileCard(Uri.parse("content://test/a.pdf"), null, "a.pdf", "1.2 MB • 3 pages", onChange = {}) }
        assertStep("Change", rule.onNode(hasText("Change") and hasClickAction()).node(), TargetStep.INLINE)
    }

    @Test
    fun `T4 a page tile's zoom, with no other target within 24 dp of its centre`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "steps-tiles.pdf")
        rule.setContent {
            Box(Modifier.size(360.dp, 600.dp)) {
                UnifiedPdfPreview(uri, 2, mode = PreviewMode.GRID, selectedPages = setOf(0), onToggleSelection = {})
            }
        }
        val targets = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        // The zooms are the only targets smaller than T2; the tiles hold them.
        val zooms = targets.filter { it.boundsInRoot.width < px(TargetStep.ACT.hit) }
        assertEquals("one zoom a tile", 2, zooms.size)
        for (zoom in zooms) {
            assertStep("the zoom", zoom, TargetStep.DENSE)
            val centre = zoom.boundsInRoot.center
            val tiles = generateSequence(zoom.parent) { it.parent }.map { it.id }.toSet()
            for (other in targets.filter { it.id != zoom.id && it.id !in tiles }) {
                assertTrue("${other.id} is within 12 dp of the zoom's centre", distance(centre, other.touchBoundsInRoot) >= px(12.dp) - 0.5f)
            }
        }
    }

    @Test
    // The 6T's screen, so the numbering sheet shows whole.
    @Config(qualifiers = "w384dp-h832dp")
    fun `Page numbers' colour button starts at the format field's outline and ends with the field`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "steps-numbers.pdf")
        rule.setContent { MaterialTheme(typography = Typography) { PageNumbersView(initialUri = uri, onBack = {}, onOpenPreview = { _, _, _ -> }) } }
        rule.waitUntil("the options button shows", 60_000) {
            rule.onAllNodes(hasContentDescription("Numbering options")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasContentDescription("Numbering options") and hasClickAction()).performClick()
        rule.waitForIdle()
        val fieldNode = rule.onNode(hasSetTextAction() and hasText("Format ({n})"))
        val field = fieldNode.node().boundsInRoot
        val colour = rule.onNode(hasText("Color") and hasClickAction()).node().boundsInRoot
        // The outline, where the field draws its border: right of the label's gap, past the 12 dp rounded corner.
        val outlineTop = field.top + fieldNode.drawnTop(listOf(14, 18, 22, 26).map { (field.width - px(it.dp)).toInt() })
        assertEquals("the colour button starts at the field's outline", outlineTop, colour.top, px(1.dp))
        assertEquals("the colour button ends with the field", field.bottom, colour.bottom, px(1.dp))
    }

    @Test
    // The 6T's screen and a long file, so the screen scrolls.
    @Config(qualifiers = "w384dp-h832dp")
    fun `Split's Select all takes a tap at its edge over the first row of tiles, scrolled or not`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(12), "steps-split.pdf")
        rule.setContent { SplitView(initialUri = uri, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil("Select all shows", 60_000) { rule.onAllNodes(hasText("Select all")).fetchSemanticsNodes().isNotEmpty() }
        // The file card and the choices scroll with the tiles (rule 10): a swipe moves Select all and the first row together.
        val screen = rule.onNode(hasScrollAction() and hasAnyDescendant(hasContentDescription("Page 1")))
        val selectAll = rule.onNode(hasText("Select all") and hasClickAction())
        val firstTile = rule.onNode(hasClickAction() and hasContentDescription("Page 1"))
        val gap = firstTile.node().boundsInRoot.top - selectAll.node().boundsInRoot.bottom
        val before = selectAll.node().boundsInRoot.top
        screen.performTouchInput { swipeUp(startY = centerY + 60f, endY = centerY - 60f) }
        rule.waitForIdle()
        assertTrue("Select all scrolled", selectAll.node().boundsInRoot.top < before - 0.5f)
        assertEquals("the tiles stay below Select all", gap, firstTile.node().boundsInRoot.top - selectAll.node().boundsInRoot.bottom, 0.5f)
        assertTrue("the tiles start below Select all", gap >= 0f)
        selectAll.performTouchInput { click(Offset(centerX, height - 1f)) }
        rule.waitForIdle()
        assertTrue("Select all took the tap", rule.onAllNodes(hasText("12 / 12 pages selected")).fetchSemanticsNodes().isNotEmpty())
    }

    private fun distance(point: Offset, rect: Rect): Float {
        val dx = max(max(rect.left - point.x, 0f), point.x - rect.right)
        val dy = max(max(rect.top - point.y, 0f), point.y - rect.bottom)
        return sqrt(dx * dx + dy * dy)
    }
}
