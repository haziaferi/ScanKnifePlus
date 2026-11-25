package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.HOME_ROUTE
import com.paperknifeplus.app.MainBottomBar
import com.paperknifeplus.app.R
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.paperknifeplus.app.ui.theme.TargetStep
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R6's labels (decisions 1 and 2 of 2026-11-24) on the seven tools that save one new PDF, each with the line over its button that says so;
 * Protect's password field (decision 5); the bottom bar's tab weights (decision 7).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h2400dp")
class ToolLabelsTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private val savesNew get() = context.getString(R.string.tool_saves_new_pdf)

    private fun shown(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** The tool [id] with [pdf] loaded, at [fontScale] times the text size, once [ready] shows. */
    private fun open(id: String, ready: String, pdf: ByteArray = TestPdfs.plain(3), fontScale: Float = 1f) {
        val uri = TestPdfs.write(context.cacheDir, pdf, "labels-$id.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PaperKnifePlusTheme { ToolCatalog.byId(id)!!.screen(ToolLaunch(uri, null, onBack = {}, onOpenPreview = { _, _, _ -> })) }
            }
        }
        rule.waitUntil("$id shows $ready", 60_000) { shown(ready) }
    }

    /** The tool [id] shows [label] on its button, with the line that says it saves a new PDF. */
    private fun assertLabelled(id: String, label: String, pdf: ByteArray = TestPdfs.plain(3)) {
        open(id, label, pdf)
        rule.onNode(hasText(label) and hasClickAction()).assertExists()
        rule.onNode(hasText(savesNew)).assertExists()
    }

    @Test fun `Compress says Compress PDF`() = assertLabelled("compress", "Compress PDF")
    @Test fun `Grayscale says Convert to grayscale`() = assertLabelled("grayscale", "Convert to grayscale")
    @Test fun `Links says Remove links`() = assertLabelled("links", "Remove links", TestPdfs.linked(3))
    @Test fun `Protect says Protect PDF`() = assertLabelled("protect", "Protect PDF")
    @Test fun `Repair says Repair PDF`() = assertLabelled("repair", "Repair PDF")

    @Test
    fun `Crop says Crop PDF once the frame moves`() {
        open("crop", "Move a corner to crop")
        rule.onNode(hasText(savesNew)).assertExists()
        rule.onNode(hasContentDescription("Left edge")).performSemanticsAction(SemanticsActions.SetProgress) { it(0.1f) }
        rule.waitForIdle()
        rule.onNode(hasText("Crop PDF") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun `Split counts its pages, one page singular`() {
        open("split", "Extract 0 pages")
        rule.onNode(hasText(savesNew)).assertExists()
        rule.onAllNodes(hasClickAction() and hasContentDescription(context.getString(R.string.tool_page_n, 1)))[0].performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Extract 1 page") and hasClickAction()).assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "it-w384dp-h2400dp")
    fun `in Italian Split's one page is Estrai 1 pagina`() {
        open("split", "Estrai 0 pagine")
        rule.onAllNodes(hasClickAction() and hasContentDescription(context.getString(R.string.tool_page_n, 1)))[0].performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Estrai 1 pagina") and hasClickAction()).assertExists()
        rule.onNode(hasText("Salva un nuovo PDF. L'originale resta com'è.")).assertExists()
    }

    @Test
    fun `Protect's password is masked until shown, its toggle T3`() {
        open("protect", "Protect PDF")
        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("secret")
        rule.waitForIdle()
        assertEquals("masked by default", "•".repeat(6), field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        val show = rule.onNode(hasContentDescription("Show password") and hasClickAction())
        val hit = show.fetchSemanticsNode().touchBoundsInRoot
        val least = with(rule.density) { TargetStep.INLINE.hit.toPx() } - 0.5f
        assertTrue("the toggle's hit area is ${hit.width} x ${hit.height} px", hit.width >= least && hit.height >= least)
        show.performClick()
        rule.waitForIdle()
        assertEquals("shown after the toggle", "secret", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        rule.onNode(hasContentDescription("Hide password") and hasClickAction()).assertExists()
    }

    /** The bottom bar's selected tab draws Bold, the others Medium, a face Plus Jakarta Sans has (SemiBold fell back to Bold). */
    @Test
    fun `the bottom bar's unselected tabs are Medium`() {
        rule.setContent { PaperKnifePlusTheme { MainBottomBar(HOME_ROUTE, onNavigate = {}, onScan = {}) } }
        fun weight(label: String): FontWeight? {
            val node = rule.onNode(hasText(label), useUnmergedTree = true).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            return layouts.single().layoutInput.style.fontWeight
        }
        assertEquals(FontWeight.Bold, weight("Home"))
        assertEquals(FontWeight.Medium, weight("History"))
    }
}

/**
 * The pinned bar with its new line at twice the text size on a 320 x 640 dp phone, in Italian (the longest labels): the screen's scrolling
 * content keeps room over it. Each test prints what it measured.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(fontScale = 2f, qualifiers = "it-w320dp-h640dp")
class PinnedBarSmallPhoneTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun measure(id: String, pdf: ByteArray = TestPdfs.plain(3)) {
        val savesNew = context.getString(R.string.tool_saves_new_pdf)
        val uri = TestPdfs.write(context.cacheDir, pdf, "bar-$id.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                PaperKnifePlusTheme { ToolCatalog.byId(id)!!.screen(ToolLaunch(uri, null, onBack = {}, onOpenPreview = { _, _, _ -> })) }
            }
        }
        rule.waitUntil("$id shows its pinned bar", 60_000) { rule.onAllNodes(hasText(savesNew)).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        val dp = rule.density.density
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        // The bar's rule (1 dp) and top padding (12 dp) sit over its first line.
        val line = rule.onNode(hasText(savesNew)).fetchSemanticsNode().boundsInRoot
        val barTop = line.top - 13 * dp
        val content = rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().maxOf { it.boundsInRoot.height }
        val bar = (root.bottom - barTop) / dp
        println(
            "$id at 2.0x, 320 x 640 dp, Italian: pinned bar ${"%.0f".format(bar)} dp (its new line ${"%.0f".format(line.height / dp)} dp), " +
                "scrolling content ${"%.0f".format(content / dp)} dp"
        )
        assertTrue("$id: the content scrolls in ${content / dp} dp", content / dp >= MIN_CONTENT_DP)
    }

    @Test fun `Compress`() = measure("compress")
    @Test fun `Crop`() = measure("crop")
    @Test fun `Grayscale`() = measure("grayscale")
    @Test fun `Links`() = measure("links", TestPdfs.linked(3))
    @Test fun `Protect`() = measure("protect")
    @Test fun `Repair`() = measure("repair")
    @Test fun `Split`() = measure("split")

    private companion object {
        /** The least the content keeps in view over the bar, a quarter of the window: Crop and Grayscale keep about 177 dp. */
        const val MIN_CONTENT_DP = 160f
    }
}
