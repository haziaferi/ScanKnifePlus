package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit

/**
 * Merge's screen: Change while added files are still being read, running out of memory while saving, a file that cannot be read at its
 * prompt or with a handed password, and the emptied list.
 */
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

    @Test
    fun `running out of memory while saving shows the failure and removes the file the save created`() {
        val (created, file) = TestPdfs.createdDocument(context, "huge-merged.pdf")
        val first = TestPdfs.OutOfMemorySource(context, TestPdfs.plain(1), "huge-merge.pdf")
        val added = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "merge-second.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(created, picked = listOf(added))) {
                MergeView(initialUri = first.uri, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(10_000) { shown(ADD_MORE) }
        rule.onNodeWithText(ADD_MORE).performClick()
        rule.waitUntil(10_000) { shown(MERGE) }

        first.runOutOfMemory()
        rule.onNodeWithText(MERGE).performClick()
        // Reading the screen runs the main thread's queue, where the failure handler shows its message and goes back to the list.
        rule.waitUntil(30_000) { shown(MERGE) && ShadowToast.getTextOfLatestToast() == TestPdfs.OUT_OF_MEMORY }
        assertFalse("the created file is removed", file.exists())
    }

    private fun decryptedCopies() = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("decrypted_") }

    /** Types the password into the prompt opened for the [index]th locked entry of [name], and unlocks. */
    private fun unlock(name: String, index: Int = 0) {
        rule.onAllNodes(hasContentDescription("Unlock $name"))[index].performClick()
        rule.onNode(hasSetTextAction()).performTextInput(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()
    }

    @Test
    @Config(qualifiers = "w400dp-h1400dp") // Tall enough for all three entries to be laid out.
    fun `an entry found unreadable at its prompt leaves the list alone, the same file's other entries staying`() {
        val source = TestPdfs.OutOfMemorySource(context, TestPdfs.userPassword(pages = 2), "merge-twice.pdf")
        val name = getUriDetails(context, source.uri).name
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(listOf(source.uri, source.uri))) {
                MergeView(initialUri = source.uri, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Unlock $name")).fetchSemanticsNodes().isNotEmpty() }
        unlock(name)
        // The list, not the loading screen that stands in for it while the password is checked.
        rule.waitUntil(30_000) { shown(ADD_MORE) && rule.onAllNodes(hasContentDescription("Unlock $name")).fetchSemanticsNodes().isEmpty() }
        val copy = decryptedCopies().single()
        // The same file twice more, both locked.
        rule.onNodeWithText(ADD_MORE).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Unlock $name")).fetchSemanticsNodes().size == 2 }

        source.runOutOfMemory()
        unlock(name)
        rule.waitUntil(30_000) { shown(ADD_MORE) && !shown("Password required") && ShadowToast.getTextOfLatestToast() == "Can't read this PDF." }
        rule.onAllNodes(hasContentDescription("Unlock $name")).assertCountEquals(1)
        rule.onAllNodes(hasContentDescription("Remove $name")).assertCountEquals(2)
        assertTrue("the unlocked entry keeps its decrypted copy", copy.exists())
    }

    @Test
    fun `a password handed over for a file it can't read adds nothing`() {
        val source = TestPdfs.write(context.cacheDir, TestPdfs.damagedProtected(), "merge-handed-damaged.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(emptyList())) {
                MergeView(initialUri = source, initialPassword = TestPdfs.USER_PASSWORD, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(30_000) { shown("Choose PDFs") && ShadowToast.getTextOfLatestToast() == "Can't read this PDF." }
        rule.onNode(choose).assertExists()
        rule.onNodeWithText("Locked").assertDoesNotExist()
    }

    @Test
    fun `the last entry found unreadable at its prompt goes back to the pick screen`() {
        val source = TestPdfs.write(context.cacheDir, TestPdfs.damagedProtected(), "merge-damaged.pdf")
        val name = getUriDetails(context, source).name
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(emptyList())) {
                MergeView(initialUri = source, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Unlock $name")).fetchSemanticsNodes().isNotEmpty() }
        unlock(name)
        rule.waitUntil(30_000) { ShadowToast.getTextOfLatestToast() == "Can't read this PDF." }
        rule.onNode(choose).assertExists()
    }

    @Test
    fun `removing the last file goes back to the pick screen`() {
        val source = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "merge-removed.pdf")
        val name = getUriDetails(context, source).name
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(emptyList())) {
                MergeView(initialUri = source, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(10_000) { shown(ADD_MORE) }
        rule.onNode(hasContentDescription("Remove $name")).performClick()
        rule.onNode(choose).assertExists()
        rule.onNodeWithText("Change").assertDoesNotExist()
    }

    /** The empty screen's button; its first step has the same words. */
    private val choose = hasText("Choose PDFs") and hasClickAction()

    private companion object {
        const val ADD_MORE = "Add more"
        const val MERGE = "Merge 2 files"
    }
}
