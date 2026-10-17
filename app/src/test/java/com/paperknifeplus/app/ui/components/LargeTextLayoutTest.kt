package com.paperknifeplus.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.haziaferi.scanknifeplus.ui.folder.FolderStatus
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.ui.theme.AppPalette
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** At twice the text size, Settings rows and the tools' shared scaffold grow with their text instead of clipping it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so text wraps.
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

    private fun assertInside(what: String, inner: Rect, outer: Rect) =
        assertTrue("$what $inner is not inside $outer", inner.top >= outer.top && inner.bottom <= outer.bottom && inner.left >= outer.left && inner.right <= outer.right)

    @Test
    fun `Settings rows keep their two lines inside the row, and the palette chips stay on screen`() {
        atDoubleText {
            SettingsView(
                onThemeChange = {}, palette = AppPalette.STILL_WATER, onPaletteChange = {}, onOpenAbout = {}, onBack = {},
                folder = FolderStatus.ChooseAgain("Documents/Scans")
            )
        }
        val list = rule.onNode(hasScrollAction())
        val subtitle = "Can't reach Documents/Scans · choose it again"
        list.performScrollToNode(hasText(subtitle))
        val row = rule.onNode(hasClickAction() and hasText("Folder")).bounds()
        assertInside("the folder name", text("Folder"), row)
        assertInside("the folder status", text(subtitle), row)
        assertTrue("the status is under the name", text(subtitle).top >= text("Folder").bottom)

        list.performScrollToNode(hasText("Graphite Scholastic"))
        val chip = rule.onNode(hasClickAction() and hasText("Graphite Scholastic")).bounds()
        val screen = rule.onNode(hasScrollAction()).bounds()
        assertInside("the palette chip", text("Graphite Scholastic"), chip)
        assertTrue("the palette chip runs off screen: $chip", chip.right <= screen.right)
    }

    @Test
    fun `the tool scaffold grows with its text`() {
        val label = "COMPRESS & SAVE PDF WITH A LONGER LABEL"
        atDoubleText {
            Column {
                ToolTopBar(title = "Compress", subtitle = "OPTIMIZE PDF SIZE", showChange = true, onBack = {}, onChange = {})
                ToolFileInfo("a-rather-long-file-name-for-the-test.pdf", "1.2 MB • 3 PAGES")
                ToolOptionRow(selected = true, title = "Recommended", description = "Great balance of size and quality", onClick = {})
                ToolPrimaryButton(label, onClick = {})
                SelectionGrid(onSelect = {}, icon = Icons.Outlined.Bolt, title = "Tap to enter file", subtitle = "OPTIMIZE ANY PDF DOCUMENT")
            }
        }
        val change = rule.onNode(hasClickAction() and hasText("Change")).bounds()
        assertTrue("the title runs under Change", text("Compress").right <= change.left)
        assertTrue("the subtitle runs under Change", text("OPTIMIZE PDF SIZE").right <= change.left)

        val option = rule.onNode(hasClickAction() and hasText("Recommended")).bounds()
        assertInside("the option's description", text("Great balance of size and quality"), option)

        val button = rule.onNode(hasClickAction() and hasText(label)).bounds()
        assertInside("the button label", text(label), button)
    }
}
