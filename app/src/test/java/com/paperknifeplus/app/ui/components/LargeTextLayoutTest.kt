package com.paperknifeplus.app.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.ui.theme.AppPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** At twice the text size, Settings rows and the tools' shared scaffold grow with their text instead of clipping it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so text wraps.
// Twice the text size for every window, the sheets' dialogs too; LocalDensity alone would reach only the content window.
@Config(fontScale = 2f)
class LargeTextLayoutTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun atDoubleText(content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) { content() }
    }

    private fun SemanticsNodeInteraction.bounds(): Rect = fetchSemanticsNode().boundsInRoot

    private fun text(text: String): Rect = rule.onNodeWithText(text, useUnmergedTree = true).bounds()

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    /** Fails when [text] is cut: its own layout, measured in the space its container gave it, overflows (bounds alone cannot show this). */
    private fun assertNotClipped(text: String) {
        val layout = layoutOf(text)
        // Height overflow is a cut line; a visible end short of the text is a line dropped by maxLines or an ellipsis.
        assertFalse("$text is cut off at the bottom", layout.didOverflowHeight)
        assertEquals("$text loses its end", layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    @Test
    fun `Settings rows and palette chips show their text uncut`() {
        atDoubleText {
            SettingsView(
                onThemeChange = {}, palette = AppPalette.STILL_WATER, onPaletteChange = {}, onOpenAbout = {}, onBack = {},
                folder = FolderStatus.ChooseAgain("Documents/Scans")
            )
        }
        val list = rule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Choose again"))
        assertNotClipped("Folder")
        assertNotClipped("Choose again")
        assertNotClipped("Colour palette")
        assertNotClipped("Still Water")
        assertTrue("the value is beside the name", text("Choose again").left >= text("Folder").right)

        rule.onNodeWithText("Colour palette").performClick()
        assertEquals("the sheet measures at twice the text size", 2f, layoutOf("Graphite Scholastic").layoutInput.density.fontScale)
        assertNotClipped("Graphite Scholastic")
    }

    @Test
    fun `the tool scaffold grows with its text`() {
        val label = "Compress and save PDF with a longer label"
        val name = "a-rather-long-file-name-for-the-test.pdf"
        atDoubleText {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ToolTopBar(title = "Compress", subtitle = "Optimize PDF size", showChange = true, onBack = {}, onChange = {})
                ToolFileCard(Uri.parse("content://test/a.pdf"), null, name, "1.2 MB • 3 pages", onChange = {})
                ToolOptionRow(selected = true, title = "Recommended", description = "Great balance of size and quality", onClick = {})
                ToolPrimaryButton(label, onClick = {})
            }
        }
        // The top bar's Change comes first in the tree, the file card's second.
        val changes = rule.onAllNodes(hasClickAction() and hasText("Change"))
        val barChange = changes[0].bounds()
        assertTrue("the title runs under Change", text("Compress").right <= barChange.left)
        assertTrue("the subtitle runs under Change", text("Optimize PDF size").right <= barChange.left)
        assertTrue("the file name runs beside the card's Change", text(name).right <= changes[1].bounds().left)

        val shown = listOf("Compress", "Optimize PDF size", name, "1.2 MB • 3 pages", "Recommended", "Great balance of size and quality", label)
        for (text in shown) assertNotClipped(text)
    }

    @Test
    fun `the empty tool screen's steps show their text uncut`() {
        atDoubleText { ToolEmptyState(Icons.Outlined.Bolt, "Optimize any PDF document", "Make the file smaller", onChoose = {}, onOpenRecent = null) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Your original file stays as it is"))
        // The step numbers too: their circles grow with the text instead of cutting it.
        val shown = listOf("1", "2", "3", "Optimize any PDF document", "From your folder or anywhere on this phone", "Make the file smaller",
            "Your original file stays as it is")
        for (text in shown) assertNotClipped(text)
    }

    @Test
    fun `Links' file card and counts show their text uncut`() {
        atDoubleText {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ToolFileCard(Uri.parse("content://test/a.pdf"), null, "Insurance letters.pdf", "2.4 MB • 12 pages", onChange = {}, keepsPassword = true)
                LinkCountsCard(LinkRemoval(links = 14, scripts = 1))
            }
        }
        val shown = listOf(PASSWORD_LINE, "Found in this file", "14", "links", "1", "script that runs when the file opens",
            "Nothing changes until you save. Form fields, comments and bookmarks stay.")
        for (text in shown) assertNotClipped(text)
        assertEquals("the counts sit side by side", text("14").top, text("1").top, 0.5f)
    }

    @Test
    fun `Links' counts with nothing to remove show their text uncut`() {
        atDoubleText { LinkCountsCard(LinkRemoval(links = 0, scripts = 0)) }
        for (text in listOf("Found in this file", "No links or scripts in this file", "There is nothing to remove, so nothing is saved.")) {
            assertNotClipped(text)
        }
    }

    @Test
    fun `the result screen's password line and report rows show their text uncut`() {
        atDoubleText {
            SuccessView(
                message = "Link removal complete",
                subMessage = "12 pages",
                result = ToolResult(Uri.parse("content://test/a-no-links.pdf"), "Insurance letters-no-links.pdf"),
                onDone = {},
                onProcessMore = {},
                onOpen = {},
                keepsPassword = true,
                report = listOf("Links" to 14, "Scripts that run when the file opens" to 1)
            )
        }
        val shown = listOf("Link removal complete", "Insurance letters-no-links.pdf", "12 pages", PASSWORD_LINE, "What was removed", "Links", "14",
            "Scripts that run when the file opens", "1")
        for (text in shown) assertNotClipped(text)
        assertTrue("a count is beside its label", text("1").left >= text("Scripts that run when the file opens").right)
    }

    @Test
    fun `Compare's file cards grow with their text`() {
        atDoubleText {
            Row(Modifier.width(320.dp)) {
                CompareFileCard("ORIGINAL (A)", "a-long-contract-name-2026.pdf", isSelected = true, modifier = Modifier.weight(1f)) {}
                CompareFileCard("REVISED (B)", "", isSelected = false, modifier = Modifier.weight(1f)) {}
            }
        }
        for (shown in listOf("ORIGINAL (A)", "a-long-contract-name-2026.pdf", "REVISED (B)", "Select file")) assertNotClipped(shown)
    }

    private companion object {
        const val PASSWORD_LINE = "Password-protected. The saved copy keeps this password."
    }
}
