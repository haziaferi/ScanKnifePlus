package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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
 * The page grids' arrows and first-time hint (R3, rule 10): every arrow that would do nothing at the first or last page is disabled (inert, and
 * read out as such), and the grids that open a page on touch and hold say so once, until the first touch and hold.
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

        tile(2).performTouchInput { longClick() }
        waitFor("2 / 3")
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithText(HOLD).assertDoesNotExist()
        // The touch and hold read the page; it marked nothing for deletion.
        rule.onNodeWithText(STATUS).assertExists()
        assertTrue(PreferencesManager.isPageHoldUsed(context))

        visit++
        rule.waitForIdle()
        waitFor(STATUS)
        rule.onNodeWithText(HOLD).assertDoesNotExist()
    }

    @Test
    fun `Rotate, whose tiles open no page, shows no hold hint`() {
        rule.setContent { RotateView(initialUri = pdf(3, "hint-rotate.pdf"), onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor("Rotate all 90°")
        rule.onNodeWithText(HOLD).assertDoesNotExist()
        assertFalse("its tiles take no touch and hold", SemanticsActions.OnLongClick in tile(1).fetchSemanticsNode().config)
    }

    private companion object {
        const val HOLD = "Touch and hold a page to read it."
        const val STATUS = "0 / 3 marked for deletion"
    }
}
