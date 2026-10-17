package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ui.folder.OutputCopy
import com.paperknifeplus.app.R
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast
import java.io.File

/** User answer 5: a tool output saved with the picker goes into History and is copied into the folder, which never undoes the save. */
@RunWith(RobolectricTestRunner::class)
class ToolOutputsTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A folder that answers every copy with [result] and remembers what it was asked to copy. */
    private class FakeFolder(private val result: OutputCopy) : OutputFolder {
        val copied = mutableListOf<Uri>()

        override fun copy(uri: Uri, onResult: (OutputCopy) -> Unit) {
            copied += uri
            onResult(result)
        }
    }

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        SessionManager.clearHistory()
        ShadowToast.reset()
    }

    @After
    fun tearDown() = SessionManager.clearHistory()

    private fun outputs(folder: OutputFolder) = ToolOutputs("merge", folder, context)

    @Test
    fun `a saved output goes into History under the tool's id and is copied into the folder once, without a message`() {
        val folder = FakeFolder(OutputCopy.COPIED)
        val uri = Uri.parse("content://picker/merged.pdf")
        rule.runOnUiThread { outputs(folder).saved(uri, "merged.pdf", "2 files joined", Icons.Filled.Description, 4) }

        val entry = SessionManager.history.single()
        assertEquals("merge", entry.toolId)
        assertEquals(R.string.tool_merge, entry.tag)
        assertEquals(uri, entry.uri)
        assertEquals(listOf(uri), folder.copied)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `with no folder chosen nothing is copied and nothing is said`() {
        rule.runOnUiThread { outputs(FakeFolder(OutputCopy.NO_FOLDER)).saved(Uri.parse("content://picker/a.pdf"), "a.pdf", "", Icons.Filled.Description) }
        assertEquals(1, SessionManager.history.size)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a folder to choose again says so once and keeps the History entry`() {
        rule.runOnUiThread { outputs(FakeFolder(OutputCopy.CHOOSE_AGAIN)).saved(Uri.parse("content://picker/a.pdf"), "a.pdf", "", Icons.Filled.Description) }
        assertEquals(1, SessionManager.history.size)
        assertEquals(context.getString(R.string.tool_copy_unreachable), ShadowToast.getTextOfLatestToast())
        assertEquals(1, ShadowToast.shownToastCount())
    }

    @Test
    fun `an output that is not a saved file is only recorded`() {
        val folder = FakeFolder(OutputCopy.COPIED)
        rule.runOnUiThread { outputs(folder).record("report.pdf", "120 chars", Icons.Filled.Description) }
        assertEquals(1, SessionManager.history.size)
        assertEquals(emptyList<Uri>(), folder.copied)
    }

    /** Answers every launch with [result], like a save dialog the user picked [result] in. */
    private fun pickerReturning(result: Uri) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, result)
            }
        }
    }

    @Test
    fun `a failed copy leaves the picker save of a tool untouched and says so`() {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "to-repair.pdf")
        val output = File(context.cacheDir, "repaired.pdf").apply { delete() }
        val folder = FakeFolder(OutputCopy.FAILED)
        rule.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides pickerReturning(Uri.fromFile(output)),
                LocalOutputFolder provides folder
            ) {
                RepairView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("REPAIR & SAVE PDF")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("REPAIR & SAVE PDF").performScrollTo().performClick()
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Repair Complete")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()

        assertEquals(listOf(Uri.fromFile(output)), folder.copied)
        assertEquals("repair", SessionManager.history.single().toolId)
        assertEquals(context.getString(R.string.tool_copy_failed), ShadowToast.getTextOfLatestToast())
        PDDocument.load(output).use { assertEquals(2, it.numberOfPages) }
    }
}
