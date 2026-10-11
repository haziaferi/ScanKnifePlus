package com.paperknifeplus.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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

    private fun entry(uri: Uri?, name: String = "x", createdAt: Long = System.currentTimeMillis()) =
        ActivityEntry("id-$name", name, "Tool", "1 page", Icons.Filled.Description, uri, 1, createdAt)

    @Test
    fun `sharing carries the output's own type and a read grant`() {
        for ((uri, type) in listOf(pdf to "application/pdf", zip to "application/zip")) {
            val intent = historyShareIntent(context.contentResolver, entry(uri))!!
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals(type, intent.type)
            assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
        assertNull(historyShareIntent(context.contentResolver, entry(null)))
    }

    @Test
    fun `only PDF outputs open in the reader`() {
        assertTrue(canOpenInReader(context.contentResolver, entry(pdf)))
        assertFalse(canOpenInReader(context.contentResolver, entry(zip)))
        assertFalse(canOpenInReader(context.contentResolver, entry(null)))
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

        // Newest first: merged.pdf, pages.zip, text.txt.
        val opens = rule.onAllNodes(hasText("OPEN")).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
        assertEquals(listOf(true, false, false), opens.map { !it.config.contains(SemanticsProperties.Disabled) })
        assertEquals(2, rule.onAllNodes(hasContentDescription("Share")).fetchSemanticsNodes().size)

        rule.onNodeWithText("pages.zip").performClick()
        assertNull(opened)
        rule.onNodeWithText("merged.pdf").performClick()
        assertEquals(pdf, opened)
    }

    @Test
    fun `entries get unique ids and are purged by creation time`() {
        rule.runOnUiThread {
            repeat(50) { SessionManager.addEntry("f$it.pdf", "Tool", "", Icons.Filled.Description) }
            assertEquals(50, SessionManager.history.map { it.id }.toSet().size)

            val now = 100L * 24 * 60 * 60 * 1000
            SessionManager.clearHistory()
            SessionManager.history += entry(pdf, "old", createdAt = now - 8L * 24 * 60 * 60 * 1000)
            SessionManager.history += entry(pdf, "recent", createdAt = now - 6L * 24 * 60 * 60 * 1000)
            SessionManager.purgeHistory(7, now)
            assertEquals(listOf("recent"), SessionManager.history.map { it.name })
            SessionManager.purgeHistory(0, now + 1_000_000_000L)
            assertEquals(1, SessionManager.history.size)
        }
    }

    private companion object {
        const val AUTHORITY = "com.paperknifeplus.app.test.history"
    }
}
