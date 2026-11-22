package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.ui.theme.TargetStep
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlin.math.max
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    private fun distance(point: Offset, rect: Rect): Float {
        val dx = max(max(rect.left - point.x, 0f), point.x - rect.right)
        val dy = max(max(rect.top - point.y, 0f), point.y - rect.bottom)
        return sqrt(dx * dx + dy * dy)
    }
}
