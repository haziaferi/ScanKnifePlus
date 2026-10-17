package com.paperknifeplus.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.haziaferi.scanknifeplus.ui.folder.FolderItem
import com.haziaferi.scanknifeplus.ui.folder.FolderKind
import com.haziaferi.scanknifeplus.ui.folder.HistoryFolder
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/** History over the user's folder: its files by kind, conflict copies with their actions, the search, and a folder that cannot be read. */
@RunWith(RobolectricTestRunner::class)
class HistoryFolderTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun uri(name: String) = Uri.parse("content://folder/$name")

    private val lease = FolderItem("f1", "Lease.pdf", FolderKind.SCAN, 1_200_000, 3_000, uri("Lease.pdf"))
    private val merged = FolderItem("f2", "Tax return merged.pdf", FolderKind.TOOL, 4_800_000, 2_000, uri("merged.pdf"))
    private val lost = FolderItem(
        "f3", "Lease.lost-20261014-191200.pdf", FolderKind.CONFLICT, 900, 1_000, uri("lost.pdf"), originalName = "Lease.pdf", original = lease
    )
    private val draft = FolderItem("s2", "Draft", FolderKind.SCAN, null, 500, null)
    private val folder = HistoryFolder("Documents/Scans", listOf(lease, merged, lost, draft))

    @After
    fun tearDown() = rule.runOnUiThread { SessionManager.clearHistory() }

    private fun history(folder: HistoryFolder?, onOpen: (Uri) -> Unit = {}, onChoose: () -> Unit = {}, onRefresh: () -> Unit = {}) =
        rule.setContent { HistoryView(onItemClick = { uri, _, _ -> onOpen(uri) }, folder = folder, onChooseFolder = onChoose, onRefresh = onRefresh) }

    @Test
    fun `the folder's files fill History, with Conflicts as a fourth filter`() {
        var refreshed = 0
        history(folder, onRefresh = { refreshed++ })
        rule.waitForIdle()
        assertEquals("History reads the folder again as it shows", 1, refreshed)
        rule.onNodeWithText("Everything in Documents/Scans").assertExists()
        for (name in listOf("Lease.pdf", "Tax return merged.pdf", "Lease.lost-20261014-191200.pdf")) rule.onNodeWithText(name).assertExists()
        rule.onNodeWithText("Tool output").assertExists()

        rule.onNodeWithText("Scans").performClick()
        rule.onNodeWithText("Lease.pdf").assertExists()
        rule.onNodeWithText("Tax return merged.pdf").assertDoesNotExist()

        rule.onNodeWithText("Conflicts").performClick()
        rule.onNodeWithText("Conflicts").assertIsSelected()
        rule.onNodeWithText("Lease.pdf").assertDoesNotExist()
        rule.onNodeWithText("Conflict copy").assertExists()
        rule.onNodeWithText("Kept after a sync clash", substring = true).assertExists()
    }

    @Test
    fun `a conflict copy opens itself or its original`() {
        val opened = mutableListOf<Uri>()
        history(folder, onOpen = { opened += it })
        rule.onNodeWithText("Conflicts").performClick()
        rule.onNodeWithText("Open").performClick()
        rule.onNodeWithText("Open Lease.pdf").performClick()
        assertEquals(listOf(lost.uri, lease.uri), opened)
    }

    @Test
    fun `a scan without its PDF shows but neither opens nor shares`() {
        history(folder)
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Draft"))
        rule.onNodeWithText("Draft").assertHasNoClickAction()
        rule.onNodeWithText("No PDF yet", substring = true).assertExists()
        rule.onNodeWithContentDescription("Share Draft").assertDoesNotExist()
    }

    @Test
    fun `each file keeps its Share button, which hands over its own type`() {
        history(folder)
        rule.onNodeWithContentDescription("Share Tax return merged.pdf").performClick()
        val chooser = shadowOf(rule.activity).nextStartedActivity
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/pdf", send.type)
        assertEquals(merged.uri, send.getParcelableExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun `search narrows the list by file name and says when nothing matches`() {
        history(folder)
        rule.onNode(hasSetTextAction()).performTextInput("merged")
        rule.onNodeWithText("Tax return merged.pdf").assertExists()
        rule.onNodeWithText("Lease.pdf").assertDoesNotExist()

        rule.onNodeWithContentDescription("Clear search").performClick()
        rule.onNodeWithText("Lease.pdf").assertExists()
        rule.onNode(hasSetTextAction()).performTextInput("zzz")
        rule.onNodeWithText("No file names match “zzz”.").assertExists()
    }

    @Test
    fun `a folder that cannot be read shows the warning and no list`() {
        var chosen = 0
        history(HistoryFolder("Documents/Scans", null, unreadable = true), onChoose = { chosen++ })
        rule.onNodeWithText("Can't read your folder").assertExists()
        rule.onNodeWithText("ScanKnife+ lost access to Documents/Scans", substring = true).assertExists()
        rule.onNodeWithText("All").assertDoesNotExist()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        rule.onNodeWithText("Choose the folder again").performClick()
        assertEquals(1, chosen)
    }

    @Test
    fun `a folder still being read says so instead of an empty list`() {
        history(HistoryFolder("Documents/Scans", null))
        rule.onNodeWithText("Reading your folder…").assertExists()
        rule.onNodeWithText("Nothing here yet", substring = true).assertDoesNotExist()
    }

    @Test
    fun `without a folder History lists this session, searchable, with no Conflicts filter`() {
        rule.runOnUiThread {
            SessionManager.addEntry("scan.pdf", "Scan", "3 pages", Icons.Filled.Description)
            SessionManager.addEntry("merged.pdf", "Merge", "2 files", Icons.Filled.Description)
        }
        history(null)
        rule.onNodeWithText("Files made in this session").assertExists()
        rule.onNodeWithText("Conflicts").assertDoesNotExist()
        rule.onNode(hasSetTextAction()).performTextInput("scan")
        rule.onNodeWithText("scan.pdf").assertExists()
        rule.onNodeWithText("merged.pdf").assertDoesNotExist()
    }

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
