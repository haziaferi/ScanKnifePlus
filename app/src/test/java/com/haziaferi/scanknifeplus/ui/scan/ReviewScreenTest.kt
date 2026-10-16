package com.haziaferi.scanknifeplus.ui.scan

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.paperknifeplus.app.ui.theme.ThemeMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReviewScreenTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = ReviewActions(
        onBack = { calls += "back" },
        onSelect = { calls += "select $it" },
        onMove = { calls += "move $it" },
        onSplit = { calls += "split $it" },
        onRotate = { calls += "rotate" },
        onCrop = { calls += "crop" },
        onFilter = { calls += "filter" },
        onDelete = { calls += "delete" },
        onUndo = { calls += "undo" },
        onAddPage = { calls += "add" },
        onDone = { calls += "done" },
        onDismissNotice = { calls += "dismiss" },
    )

    private fun pages(n: Int) = (1..n).map { ReviewPage("p$it", File("page$it.jpg")) }

    private fun show(ui: ReviewUi, fontScale: Float = 1f, oled: Boolean = false) {
        rule.setContent {
            Scaled(fontScale) {
                PaperKnifePlusTheme(themeMode = if (oled) ThemeMode.OLED else ThemeMode.LIGHT) { ReviewScreen(ui, actions) }
            }
        }
    }

    @Test
    fun `single page shows the page, the page actions with Delete apart, and Done without a strip`() {
        show(ReviewUi(CaptureMode.SINGLE, pages(1)))
        rule.onNodeWithText("Single page").assertIsDisplayed()
        rule.onNodeWithContentDescription("Page 1").assertExists()
        rule.onNodeWithContentDescription("Add page").assertDoesNotExist()
        for (action in listOf("Rotate", "Crop", "Filter", "Delete")) rule.onNodeWithText(action).performScrollTo().performClick()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithContentDescription("Back to scanner").performClick()
        assertEquals(listOf("rotate", "crop", "filter", "delete", "done", "back"), calls)
    }

    @Test
    fun `a deleted single page leaves an empty view, Undo and no Done`() {
        show(ReviewUi(CaptureMode.SINGLE, emptyList(), deletedNumber = 1))
        rule.onNodeWithText("Page deleted").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Page 1 deleted").assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsNotEnabled()
        rule.onNodeWithText("Rotate").assertIsNotEnabled()
        rule.onNodeWithText("Undo").performClick()
        assertEquals(listOf("undo"), calls)
    }

    @Test
    fun `multi page selects from the strip, moves the selected page with arrows and adds pages`() {
        show(ReviewUi(CaptureMode.MULTI, pages(3), selectedId = "p3"))
        rule.onNodeWithText("Multi page · 3 pages").assertIsDisplayed()
        rule.onNodeWithText("Page 3 of 3").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Move page later").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Move page earlier").performScrollTo().assertIsEnabled().performClick()
        rule.onNodeWithText("3").assertIsSelected()
        rule.onNodeWithText("1").performClick()
        rule.onNodeWithContentDescription("Add page").performClick()
        assertEquals(listOf("move -1", "select p1", "add"), calls)
    }

    @Test
    fun `the first page cannot move earlier`() {
        show(ReviewUi(CaptureMode.MULTI, pages(2), selectedId = "p1"))
        rule.onNodeWithContentDescription("Move page earlier").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Move page later").assertIsEnabled()
    }

    @Test
    fun `book shows the spread with its labels below it and a split line slider that re-splits on release`() {
        val p = pages(2)
        show(ReviewUi(CaptureMode.BOOK, p, selectedId = "p1", spread = ReviewSpread(p[0], p[1], 1, 0.5f)))
        rule.onNodeWithText("Book · 1 spread · 2 pages").assertIsDisplayed()
        rule.onNodeWithText("Page 1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Page 2").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Centre").performScrollTo().assertIsDisplayed()
        rule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0.3f..0.7f, 39)))
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
        rule.onNodeWithText("10% right of centre").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("split 0.6"), calls)
    }

    @Test
    fun `a busy edit disables the page actions and Done`() {
        show(ReviewUi(CaptureMode.MULTI, pages(2), busy = true, canSave = false))
        rule.onNodeWithText("Rotate").assertIsNotEnabled()
        rule.onNodeWithText("Delete").assertIsNotEnabled()
        rule.onNodeWithText("Done").assertIsNotEnabled()
    }

    @Test
    fun `a failed edit is reported until dismissed`() {
        show(ReviewUi(CaptureMode.SINGLE, pages(1), notice = com.paperknifeplus.app.R.string.scan_edit_failed))
        rule.onNodeWithText("That change couldn't be made. Try again.").assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    @Test
    fun `at twice the text size in OLED the actions, the strip and Done stay reachable`() {
        show(ReviewUi(CaptureMode.MULTI, pages(4), deletedNumber = 2), fontScale = 2f, oled = true)
        rule.onNodeWithText("Done").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("Undo").assertIsDisplayed()
        for (action in listOf("Rotate", "Crop", "Filter", "Delete")) rule.onNodeWithText(action).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1").assertIsDisplayed()
    }
}
