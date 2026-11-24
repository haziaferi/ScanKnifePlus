package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The page grids' arrows (R3, rule 10): every arrow that would do nothing at the first or last page is disabled (inert, and read out as such).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Tall, so the whole screen is laid out.
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
}
