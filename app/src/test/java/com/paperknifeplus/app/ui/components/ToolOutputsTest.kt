package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.storage.SavedFiles
import com.haziaferi.scanknifeplus.storage.SavedPlace
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.SavedFilesProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/** Ledger L8: a tool's saved file is listed across restarts under the tool's id, with no copy into a folder; a text read stays in the session. */
@RunWith(RobolectricTestRunner::class)
class ToolOutputsTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files get() = SavedFiles.of(context)

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        SessionManager.clearHistory()
        ShadowToast.reset()
    }

    @After
    fun tearDown() {
        SessionManager.clearHistory()
        SavedFilesProvider.forget(context)
    }

    private fun outputs() = ToolOutputs("merge", context)

    @Test
    fun `a saved output is listed in the index under the tool's id, not in the session, and nothing is said`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "merged.pdf")
        rule.runOnUiThread { outputs().saved(uri, "merged.pdf", "2 files joined", Icons.Filled.Description, 4) }
        files.awaitWrites()

        val entry = files.entries().single()
        assertEquals("merge", entry.tool)
        assertEquals(uri, entry.uri)
        assertEquals(SavedPlace.ELSEWHERE, entry.place)
        assertEquals(4, entry.pages)
        assertTrue(SessionManager.history.isEmpty())
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `an output that is not a saved file is only this session's`() {
        rule.runOnUiThread { outputs().record("report.pdf", "120 chars", Icons.Filled.Description) }
        files.awaitWrites()
        assertEquals(1, SessionManager.history.size)
        assertTrue(files.entries().isEmpty())
    }

    @Test
    fun `a tool saved with the picker lists its output and copies it nowhere`() {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "to-repair.pdf")
        val output = File(context.cacheDir, "repaired.pdf").apply { delete() }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(Uri.fromFile(output))) {
                RepairView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Repair PDF")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Repair PDF").performClick()
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Repair complete")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        files.awaitWrites()

        assertEquals("repair", files.entries().single().tool)
        assertNull("no folder message", ShadowToast.getTextOfLatestToast())
        PDDocument.load(output).use { assertEquals(2, it.numberOfPages) }
    }
}
