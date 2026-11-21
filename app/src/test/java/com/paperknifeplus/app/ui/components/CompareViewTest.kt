package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.CompositionJobs
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class CompareViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun emptyCards() = rule.onAllNodes(hasText("Select file")).fetchSemanticsNodes().size

    @Test
    fun `a locked file whose prompt is cancelled is not kept as file A`() {
        val locked = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(), "locked-compare.pdf")
        rule.setContent { CompareView(initialUri = locked, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Unlock")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(2, emptyCards())
    }

    @Test
    fun `a readable file becomes file A`() {
        val plain = TestPdfs.write(context.cacheDir, TestPdfs.plain(), "plain-compare.pdf")
        rule.setContent { CompareView(initialUri = plain, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(10_000) { emptyCards() == 1 }
    }

    /** A file picker that picks [result]. */
    private fun pickerReturning(result: Uri) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, result)
            }
        }
    }

    @Test
    fun `Reset while file B is checked leaves both cards empty when the check ends`() {
        val first = TestPdfs.write(context.cacheDir, TestPdfs.plain(), "compare-first.pdf")
        val second = TestPdfs.HeldSource(context, TestPdfs.plain(), "compare-second.pdf")
        val jobs = CompositionJobs()
        rule.setContent {
            jobs.Watch()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(second.uri)) {
                CompareView(initialUri = first, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(10_000) { emptyCards() == 1 }
        second.hold()
        rule.onNodeWithText("Revised (B)").performClick()
        rule.waitUntil(10_000) { second.reached.count == 0L }
        val check = jobs.waitingOnIo()
        assertTrue("the check is held in its read", check.isNotEmpty())

        rule.onNodeWithText("Reset").performClick()
        assertEquals(2, emptyCards())
        second.release()
        assertTrue(second.closed.await(30, TimeUnit.SECONDS))
        // Once the check of file B has ended, it must not have filled a card of the reset screen.
        rule.waitUntil(30_000) { check.all { it.isCompleted } }
        rule.waitForIdle()
        assertEquals(2, emptyCards())
    }
}
