package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.testing.assertTextOnTheScale
import com.paperknifeplus.app.ui.theme.DocumentContent
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The type scale (rule 1) in the dialogs and sheets the target walks never open, at the default text size: Rearrange's Move to, Sign's and
 * Watermark's options, Page numbers' position and colour sheets. A locked file's prompt is walked already (Unlock, configuring).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h2400dp")
class DialogTextScaleTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /** The tool [id] with a 3-page file loaded. */
    private fun open(id: String) {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "scale-$id.pdf")
        rule.setContent { PaperKnifePlusTheme { ToolCatalog.byId(id)!!.screen(ToolLaunch(uri, null, onBack = {}, onOpenPreview = { _, _, _ -> })) } }
        rule.waitUntil("$id shows its pages", 60_000) { rule.onAllNodes(hasClickAction() and hasContentDescription("Page 1")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun tap(matcher: SemanticsMatcher) {
        rule.onAllNodes(matcher)[0].performClick()
        rule.waitForIdle()
    }

    private fun tapFirstPage() = tap(hasClickAction() and hasContentDescription("Page 1"))

    @Test
    fun `Rearrange's Move to dialog`() {
        open("rearrange")
        tapFirstPage()
        tap(hasText("Move to…") and hasClickAction())
        rule.assertTextOnTheScale("Rearrange's Move to dialog")
    }

    @Test
    fun `Sign's options`() {
        open("sign")
        tapFirstPage()
        tap(hasText("Add sign") and hasClickAction())
        rule.assertTextOnTheScale("Sign's options")
    }

    @Test
    fun `Watermark's options`() {
        open("watermark")
        tapFirstPage()
        tap(hasText("Add watermark") and hasClickAction())
        rule.assertTextOnTheScale("Watermark's options")
    }

    @Test
    fun `Page numbers' position sheet`() {
        open("page-numbers")
        tap(hasContentDescription("Numbering options") and hasClickAction())
        tap(hasText("Position", substring = true) and hasClickAction())
        rule.assertTextOnTheScale("Page numbers' position sheet")
    }

    @Test
    fun `Page numbers' colour sheet`() {
        open("page-numbers")
        tap(hasContentDescription("Numbering options") and hasClickAction())
        tap(hasText("Color") and hasClickAction())
        rule.assertTextOnTheScale("Page numbers' colour sheet")
    }

    /** The preview's number sits on the font's own line, as near the page's edge as it will print: no line height pads it. */
    @Test
    fun `the page number on a page's preview takes no more than its own line`() {
        open("page-numbers")
        val numbers = rule.onAllNodes(SemanticsMatcher.keyIsDefined(DocumentContent), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the preview shows its numbers", numbers.isNotEmpty())
        val layouts = mutableListOf<TextLayoutResult>()
        numbers.first().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        // 14 pt prints as a 7 sp number on the preview; its own line is about 1.3 times that.
        val most = with(rule.density) { (7 * 1.5).sp.toPx() }
        assertTrue("the number's line is ${layout.size.height} px, past $most", layout.size.height <= most)
    }
}
