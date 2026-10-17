package com.paperknifeplus.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class HistoryTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pdf = Uri.parse("content://$AUTHORITY/merged.pdf")
    private val zip = Uri.parse("content://$AUTHORITY/pages.zip")

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(HomeViewTest.TypedProvider::class.java, AUTHORITY)
    }

    @After
    fun tearDown() = rule.runOnUiThread { SessionManager.clearHistory() }

    private fun entry(uri: Uri?) = ActivityEntry("id", "x", "Tool", "1 page", Icons.Filled.Description, uri, 1)

    @Test
    fun `sharing carries the output's own type and a read grant`() {
        for ((uri, type) in listOf(pdf to "application/pdf", zip to "application/zip")) {
            val intent = historyShareIntent(entry(uri), historyEntryMime(context.contentResolver, uri))!!
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals(type, intent.type)
            assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
        assertNull(historyShareIntent(entry(null), "application/pdf"))
    }

    @Test
    fun `only PDF outputs open in the reader`() {
        assertTrue(canOpenInReader(historyEntryMime(context.contentResolver, pdf)))
        assertFalse(canOpenInReader(historyEntryMime(context.contentResolver, zip)))
        assertFalse(canOpenInReader(null))
    }

    @Test
    fun `only PDF rows open, and Share needs a file`() {
        rule.runOnUiThread {
            SessionManager.addEntry("text.txt", "To Text", "5 chars", Icons.Filled.Description)
            SessionManager.addEntry("pages.zip", "PDF to ZIP", "3 pages", Icons.Filled.FolderZip, zip)
            SessionManager.addEntry("merged.pdf", "Merge", "2 files", Icons.Filled.Description, pdf, 4)
        }
        var opened: Uri? = null
        rule.setContent { HistoryView(onItemClick = { uri, _, _ -> opened = uri }) }

        // Types are resolved off the main thread, so the PDF row turns clickable shortly after.
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("merged.pdf") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("pages.zip").assertHasNoClickAction()
        rule.onNodeWithText("text.txt").assertHasNoClickAction()
        assertEquals(2, rule.onAllNodes(hasContentDescription("Share", substring = true)).fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Share text.txt").assertDoesNotExist()

        rule.onNodeWithText("merged.pdf").performClick()
        assertEquals(pdf, opened)
    }

    @Test
    fun `the filter chips split scans from tool outputs`() {
        rule.runOnUiThread {
            SessionManager.addEntry("scan.pdf", "Scan", "3 pages", Icons.Filled.DocumentScanner)
            SessionManager.addEntry("merged.pdf", "Merge", "2 files", Icons.Filled.Description)
        }
        rule.setContent { HistoryView(onItemClick = { _, _, _ -> }) }

        rule.onNodeWithText("All").assertIsSelected()
        rule.onNodeWithText("scan.pdf").assertExists()
        rule.onNodeWithText("merged.pdf").assertExists()

        rule.onNodeWithText("Scans").performClick()
        rule.onNodeWithText("Scans").assertIsSelected()
        rule.onNodeWithText("All").assertIsNotSelected()
        rule.onNodeWithText("scan.pdf").assertExists()
        rule.onNodeWithText("merged.pdf").assertDoesNotExist()

        rule.onNodeWithText("Tool outputs").performClick()
        rule.onNodeWithText("scan.pdf").assertDoesNotExist()
        rule.onNodeWithText("merged.pdf").assertExists()
    }

    @Test
    fun `an empty filter says so, and there is no back arrow`() {
        rule.runOnUiThread { SessionManager.addEntry("merged.pdf", "Merge", "2 files", Icons.Filled.Description) }
        rule.setContent { HistoryView(onItemClick = { _, _, _ -> }) }
        rule.onNodeWithText("Scans").performClick()
        rule.onNodeWithText("No scans yet.").assertExists()
        rule.onNodeWithContentDescription("Back to Home").assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Real text measurement, so long names wrap.
    fun `at 2x text a row keeps its tag clear of the name`() {
        val long = "Insurance letters compressed for the accountant.pdf"
        rule.runOnUiThread { SessionManager.addEntry(long, "Compress", "Optimized", Icons.Filled.Description) }
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                HistoryView(onItemClick = { _, _, _ -> })
            }
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Compress"))
        val name = rule.onNodeWithText(long).fetchSemanticsNode().boundsInRoot
        val tag = rule.onNodeWithText("Compress").fetchSemanticsNode().boundsInRoot
        assertTrue("name $name overlaps the tag $tag", name.right <= tag.left)
        assertTrue("the name wraps instead of clipping", name.height > tag.height)
    }

    @Test
    fun `entries get unique ids`() {
        rule.runOnUiThread {
            repeat(50) { SessionManager.addEntry("f$it.pdf", "Tool", "", Icons.Filled.Description) }
            assertEquals(50, SessionManager.history.map { it.id }.toSet().size)
        }
    }

    private companion object {
        const val AUTHORITY = "com.paperknifeplus.app.test.history"
    }
}
