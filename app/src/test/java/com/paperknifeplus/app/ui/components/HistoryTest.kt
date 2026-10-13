package com.paperknifeplus.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun `OPEN is disabled for ZIP and file-less entries, and Share needs a file`() {
        rule.runOnUiThread {
            SessionManager.addEntry("text.txt", "To Text", "5 chars", Icons.Filled.Description)
            SessionManager.addEntry("pages.zip", "PDF to ZIP", "3 pages", Icons.Filled.FolderZip, zip)
            SessionManager.addEntry("merged.pdf", "Merge", "2 files", Icons.Filled.Description, pdf, 4)
        }
        var opened: Uri? = null
        rule.setContent { HistoryView(onItemClick = { uri, _, _ -> opened = uri }) }

        // Newest first: merged.pdf, pages.zip, text.txt. Types are resolved off the main thread, so OPEN turns on shortly after.
        fun enabled() = rule.onAllNodes(hasText("OPEN")).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
            .map { !it.config.contains(SemanticsProperties.Disabled) }
        rule.waitUntil(10_000) { enabled() == listOf(true, false, false) }
        assertEquals(2, rule.onAllNodes(hasContentDescription("Share")).fetchSemanticsNodes().size)

        rule.onNodeWithText("pages.zip").performClick()
        assertNull(opened)
        rule.onNodeWithText("merged.pdf").performClick()
        assertEquals(pdf, opened)
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
