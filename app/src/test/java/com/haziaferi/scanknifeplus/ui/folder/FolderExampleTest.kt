package com.haziaferi.scanknifeplus.ui.folder

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.sameColour
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Ledger L8 item 6: Folder setup's list of what the folder will hold is marked as an example, with plain sample names and neutral bullets. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real drawing, so the bullets' colours can be read.
@Config(qualifiers = "w384dp-h2400dp") // Tall enough that the whole list shows.
class FolderExampleTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    @Test
    fun `the example list sits under an Example label, its rows plainly samples, and every bullet one neutral colour`() {
        var neutral = Color.Unspecified
        rule.setContent {
            PaperKnifePlusTheme {
                neutral = LocalScanPalette.current.mu
                FolderSetupScreen(FolderStatus.NotChosen, 0, choosing = false, failed = false, onChoose = {}, onNotNow = {})
            }
        }
        val label = rule.onNodeWithText("Example").fetchSemanticsNode().boundsInRoot
        val rows = listOf("Example scan.pdf", "Example merged.pdf", "Scan sources/Example scan/ · pages you can edit later")
        for (row in rows) {
            assertTrue("$row is under the label", rule.onNodeWithText(row).fetchSemanticsNode().boundsInRoot.top >= label.bottom)
        }
        rule.onNodeWithText("Not now · keep files in the app").assertExists()

        val bullets = rule.onAllNodesWithTag(EXAMPLE_BULLET, useUnmergedTree = true)
        assertEquals(rows.size, bullets.fetchSemanticsNodes().size)
        val colours = rows.indices.map { i -> bullets[i].captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] } }
        for (colour in colours) assertTrue("bullet $colour is the palette's neutral $neutral", sameColour(colour, neutral))
    }
}
