package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MergeOrderTest {
    private val uri = Uri.EMPTY
    private val files = listOf(
        MergeFile(id = 10, uri = uri, name = "a.pdf", size = "1 KB", isLocked = false),
        MergeFile(id = 11, uri = uri, name = "a.pdf", size = "1 KB", isLocked = false),
        MergeFile(id = 12, uri = uri, name = "b.pdf", size = "2 KB", isLocked = true)
    )

    @Test
    fun `moving down swaps an entry with the next one and keeps each id on its file`() {
        val moved = moveMergeFile(files, 0, +1)
        assertEquals(listOf(11L, 10L, 12L), moved.map { it.id })
        moved.forEach { file -> assertSame(files.single { it.id == file.id }, file) }
    }

    @Test
    fun `moving up swaps an entry with the previous one`() {
        val moved = moveMergeFile(files, 2, -1)
        assertEquals(listOf(10L, 12L, 11L), moved.map { it.id })
        assertEquals("b.pdf", moved[1].name)
        assertEquals(true, moved[1].isLocked)
    }

    @Test
    fun `moves past either end change nothing`() {
        assertSame(files, moveMergeFile(files, 0, -1))
        assertSame(files, moveMergeFile(files, 2, +1))
        assertSame(files, moveMergeFile(files, 5, -1))
    }

    @Test
    fun `a round trip restores the order`() {
        assertEquals(files, moveMergeFile(moveMergeFile(files, 1, +1), 2, -1))
    }
}

/**
 * Rearrange end to end: pages that inherit Resources, MediaBox and Rotate from the page tree, a file unlocked at the prompt, a password
 * handed over by the reader that is wrong or opens a damaged file, and running out of memory while saving.
 */
@RunWith(RobolectricTestRunner::class)
class RearrangeViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun pickerReturning(result: Uri) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, result)
            }
        }
    }

    @Test
    fun `reordered pages keep their inherited attributes`() {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.inheritedAttributes(2), "inherited.pdf")
        val output = File(context.cacheDir, "reordered.pdf").apply { delete() }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(Uri.fromFile(output))) {
                RearrangeView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Save PDF")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Save PDF").performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Rearrange complete")).fetchSemanticsNodes().isNotEmpty() }

        PDDocument.load(output).use { doc ->
            assertEquals(2, doc.numberOfPages)
            for (page in doc.pages) {
                assertNotNull(page.resources?.getFont(TestPdfs.INHERITED_FONT))
                assertEquals(TestPdfs.INHERITED_MEDIA_BOX.toString(), page.mediaBox.toString())
                assertEquals(TestPdfs.INHERITED_ROTATION, page.rotation)
            }
        }
    }

    @Test
    fun `a file unlocked at the prompt is saved with its password, not from the decrypted copy`() {
        val input = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked-rearrange.pdf")
        val output = File(context.cacheDir, "locked-reordered.pdf").apply { delete() }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(Uri.fromFile(output))) {
                RearrangeView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Unlock")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Save PDF")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Save PDF").performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Rearrange complete")).fetchSemanticsNodes().isNotEmpty() }

        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(output).close() }
        PDDocument.load(output, TestPdfs.USER_PASSWORD).use { doc ->
            assertTrue(doc.isEncrypted)
            assertEquals(2, doc.numberOfPages)
        }
    }

    private fun showHanded(uri: Uri, password: String) =
        rule.setContent { RearrangeView(initialUri = uri, initialPassword = password, onBack = {}, onOpenPreview = { _, _, _ -> }) }

    @Test
    fun `a wrong password handed over by the reader opens the prompt with its error`() {
        showHanded(TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "handed-wrong-rearrange.pdf"), "wrong")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Password required")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Incorrect password, try again.").assertExists()
    }

    @Test
    fun `a handed password unlocks the file as the prompt does, through a decrypted copy`() {
        showHanded(TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "handed-copied-rearrange.pdf"), TestPdfs.USER_PASSWORD)
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Save PDF")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, context.cacheDir.listFiles().orEmpty().count { it.name.startsWith("decrypted_") })
    }

    @Test
    fun `a password handed over for a file that opens without one is dropped, so its pages count`() {
        // An owner password only: the handed one is neither, so opening with it would fail.
        showHanded(TestPdfs.write(context.cacheDir, TestPdfs.ownerOnly(pages = 2), "handed-owner-only-rearrange.pdf"), "not-needed")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("2 pages", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Password required").assertDoesNotExist()
    }

    @Test
    fun `a damaged protected file handed over with its password says it can't be read and goes back to choosing a file`() {
        showHanded(TestPdfs.write(context.cacheDir, TestPdfs.damagedProtected(), "handed-damaged-rearrange.pdf"), TestPdfs.USER_PASSWORD)
        rule.waitUntil(10_000) { ShadowToast.getTextOfLatestToast() == "Can't read this PDF." }
        rule.onNodeWithText("Choose PDF").assertExists()
        rule.onNodeWithText("Password required").assertDoesNotExist()
    }

    @Test
    fun `running out of memory while saving shows the failure and removes the file the save created`() {
        val (output, created) = TestPdfs.createdDocument(context, "huge-rearranged.pdf")
        val source = TestPdfs.OutOfMemorySource(context, TestPdfs.plain(2), "huge-rearrange.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(output)) {
                RearrangeView(initialUri = source.uri, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Save PDF")).fetchSemanticsNodes().isNotEmpty() }

        source.runOutOfMemory()
        rule.onNodeWithText("Save PDF").performClick()
        // Reading the screen runs the main thread's queue, where the failure handler shows its message and goes back to the pages.
        rule.waitUntil(30_000) {
            rule.onAllNodes(hasText("Save PDF")).fetchSemanticsNodes().isNotEmpty() && ShadowToast.getTextOfLatestToast() == "Error: test"
        }
        assertFalse("the created file is removed", created.exists())
    }
}
