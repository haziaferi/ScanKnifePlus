package com.paperknifeplus.app.ui.components

import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.haziaferi.scanknifeplus.ui.folder.FolderItem
import com.haziaferi.scanknifeplus.ui.folder.FolderKind
import com.haziaferi.scanknifeplus.ui.folder.HistoryFolder
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** History's folder rows at large text sizes. */
@RunWith(RobolectricTestRunner::class)
class HistoryFolderLayoutTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private fun uri(name: String) = Uri.parse("content://folder/$name")

    private val lease = FolderItem("f1", "Lease.pdf", FolderKind.SCAN, 1_200_000, 3_000, uri("Lease.pdf"))
    private val lost = FolderItem(
        "f3", "Lease.lost-20261014-191200.pdf", FolderKind.CONFLICT, 900, 1_000, uri("lost.pdf"), originalName = "Lease.pdf", original = lease
    )

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so text wraps.
    fun `at 2x text a conflict copy keeps its note above its buttons`() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                HistoryView(onItemClick = { _, _, _ -> }, folder = HistoryFolder("Documents/Scans", listOf(lost)))
            }
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Open Lease.pdf"))
        val name = rule.onNodeWithText(lost.name, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val tag = rule.onNodeWithText("Conflict copy", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val note = rule.onNodeWithText("Kept after a sync clash", substring = true).fetchSemanticsNode().boundsInRoot
        val button = rule.onNodeWithText("Open Lease.pdf").fetchSemanticsNode().boundsInRoot
        assertTrue("name overlaps the tag", name.right <= tag.left)
        assertTrue("the note is drawn over the row", note.top >= name.bottom)
        assertTrue("the buttons are drawn over the note", button.top >= note.bottom)
    }
}
