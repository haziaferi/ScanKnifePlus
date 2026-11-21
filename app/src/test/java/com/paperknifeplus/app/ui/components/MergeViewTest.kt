package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.CompositionJobs
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** Merge's screen: Change while added files are still being read. */
@RunWith(RobolectricTestRunner::class)
class MergeViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /** A file picker that picks [result], as Choose PDFs and Add more launch it. */
    private fun pickerReturning(result: List<Uri>) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, result)
            }
        }
    }

    private fun shown(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `Change while added files are read leaves the empty screen when the reading ends`() {
        val first = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "merge-first.pdf")
        val added = TestPdfs.HeldSource(context, TestPdfs.plain(2), "merge-added.pdf")
        val jobs = CompositionJobs()
        rule.setContent {
            jobs.Watch()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(listOf(added.uri))) {
                MergeView(initialUri = first, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(10_000) { shown(ADD_MORE) }
        added.hold()
        rule.onNodeWithText(ADD_MORE).performClick()
        rule.waitUntil(10_000) { added.reached.count == 0L }
        val check = jobs.waitingOnIo()
        assertTrue("the check is held in its read", check.isNotEmpty())

        rule.onNodeWithText("Change").performClick()
        rule.onNode(choose).assertExists()
        added.release()
        assertTrue(added.closed.await(30, TimeUnit.SECONDS))
        // Once the check of the added file has ended, it must not have brought the list back on the emptied screen.
        rule.waitUntil(30_000) { check.all { it.isCompleted } }
        rule.waitForIdle()
        assertFalse(shown(ADD_MORE))
        rule.onNode(choose).assertExists()
    }

    /** The empty screen's button; its first step has the same words. */
    private val choose = hasText("Choose PDFs") and hasClickAction()

    private companion object {
        const val ADD_MORE = "Add more"
    }
}
