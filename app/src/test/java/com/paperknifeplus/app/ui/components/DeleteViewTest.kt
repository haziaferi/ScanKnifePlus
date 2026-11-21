package com.paperknifeplus.app.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.storage.TestDocumentsProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DeleteViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    @Test
    fun `opened with a file, Delete goes straight to page selection`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "three.pdf")
        rule.setContent { DeleteView(initialUri = uri, onBack = {}, onOpenPreview = { _, _, _ -> }) }

        rule.waitUntil(10_000) { rule.onAllNodes(hasText("marked for deletion", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("0 / 3 marked for deletion").assertExists()
        rule.onNodeWithText("Choose PDF").assertDoesNotExist()
    }

    @Test
    fun `opened with an encrypted file and its password, Delete unlocks it without asking`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked.pdf")
        rule.setContent { DeleteView(initialUri = uri, initialPassword = TestPdfs.USER_PASSWORD, onBack = {}, onOpenPreview = { _, _, _ -> }) }

        rule.waitUntil(10_000) { rule.onAllNodes(hasText("marked for deletion", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("0 / 2 marked for deletion").assertExists()
        rule.onNodeWithText("Password required").assertDoesNotExist()
    }

    @Test
    fun `an owner-password-only file opens without the password prompt`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.ownerOnly(pages = 2), "owner-only.pdf")
        rule.setContent { DeleteView(initialUri = uri, onBack = {}, onOpenPreview = { _, _, _ -> }) }

        rule.waitUntil(10_000) { rule.onAllNodes(hasText("marked for deletion", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("0 / 2 marked for deletion").assertExists()
        rule.onNodeWithText("Password required").assertDoesNotExist()
    }

    @Test
    fun `a damaged protected file opened with its password says it can't be read, not that the password is wrong`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.damagedProtected(), "damaged-delete.pdf")
        rule.setContent { DeleteView(initialUri = uri, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Password required")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()

        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Password required")).fetchSemanticsNodes().isEmpty() }
        assertEquals("Can't read this PDF.", ShadowToast.getTextOfLatestToast())
        rule.onNodeWithText("Incorrect password, try again.").assertDoesNotExist()
        rule.onNodeWithText("Choose PDF").assertExists()
        assertTrue("no decrypted copy is left", context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("decrypted_") })
    }

    /**
     * An empty document in a documents provider, as the system's save picker creates it before the tool writes, served so that the tool's
     * clean-up after a failed save can remove it.
     */
    private fun createdDocument(name: String): Pair<Uri, File> {
        val folder = File(context.cacheDir, "documents").apply { deleteRecursively(); mkdirs() }
        TestDocumentsProvider.install(folder)
        val provider = ComponentName(context, TestDocumentsProvider::class.java)
        Shadows.shadowOf(context.packageManager).apply {
            addOrUpdateProvider(ProviderInfo().apply {
                authority = TestDocumentsProvider.AUTHORITY
                packageName = provider.packageName
                this.name = provider.className
            })
            addIntentFilterForProvider(provider, IntentFilter(DocumentsContract.PROVIDER_INTERFACE))
        }
        val file = File(folder, name).apply { createNewFile() }
        return DocumentsContract.buildDocumentUri(TestDocumentsProvider.AUTHORITY, "${TestDocumentsProvider.ROOT}/$name") to file
    }

    /** A save picker the user picks [output] in. */
    private fun savingTo(output: Uri) = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                dispatchResult(requestCode, output)
            }
        }
    }

    @Test
    fun `running out of memory while saving shows the failure and removes the file the save created`() {
        val (output, created) = createdDocument("huge-pages-deleted.pdf")
        assertTrue("the documents provider serves the created file", DocumentsContract.isDocumentUri(context, output))
        val source = TestPdfs.OutOfMemorySource(context, TestPdfs.plain(3), "huge.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides savingTo(output)) {
                DeleteView(initialUri = source.uri, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("0 / 3 marked for deletion")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Enter range").performClick()
        rule.onNode(hasSetTextAction()).performTextInput("1")
        rule.waitForIdle()

        source.runOutOfMemory()
        rule.onNodeWithText("Delete 1 pages").performClick()
        // Back on page selection, which the failure handler shows after its message (reading the screen runs the main thread's queue).
        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Delete 1 pages")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Error: test", ShadowToast.getTextOfLatestToast())
        assertFalse("the created file is removed", created.exists())
    }
}
