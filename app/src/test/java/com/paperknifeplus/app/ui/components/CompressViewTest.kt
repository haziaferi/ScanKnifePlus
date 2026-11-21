package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.TimeUnit

/**
 * Compress, a tool that decrypts a protected file to a cache copy: Cancel during the password check, a damaged protected file, and a
 * password handed over by the reader that is wrong or opens a damaged file.
 */
@RunWith(RobolectricTestRunner::class)
class CompressViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /** The files directly in the cache folder: decrypted copies and PDFBox's temp files would show here. */
    private fun cacheFiles() = context.cacheDir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()

    @Test
    fun `Cancel during the password check resets the tool, and the check leaves no copy and no late result`() {
        val source = TestPdfs.HeldSource(context, TestPdfs.userPassword(pages = 2), "held-compress.pdf")
        rule.setContent { CompressView(initialUri = source.uri, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Password required")).fetchSemanticsNodes().isNotEmpty() }
        val before = cacheFiles()
        source.hold()
        rule.onNode(hasSetTextAction()).performTextInput(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()
        rule.waitUntil(10_000) { source.reached.count == 0L }

        rule.onNodeWithText("Cancel").assertIsEnabled().performClick()
        rule.onNodeWithText("Password required").assertDoesNotExist()
        rule.onNodeWithText("Choose PDF").assertExists()
        source.release()
        // The check goes on to write its decrypted copy, then finds itself cancelled and deletes it.
        assertTrue(source.closed.await(30, TimeUnit.SECONDS))
        rule.waitUntil(10_000) { cacheFiles() == before }
        rule.onNodeWithText("Choose PDF").assertExists()
        rule.onNodeWithText("Compress and save PDF").assertDoesNotExist()
        assertEquals(before, cacheFiles())
    }

    @Test
    fun `a damaged protected file opened with its password says it can't be read, not that the password is wrong`() {
        val source = TestPdfs.write(context.cacheDir, TestPdfs.damagedProtected(), "damaged-compress.pdf")
        rule.setContent { CompressView(initialUri = source, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Password required")).fetchSemanticsNodes().isNotEmpty() }
        val before = cacheFiles()
        rule.onNode(hasSetTextAction()).performTextInput(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()

        rule.waitUntil(30_000) { rule.onAllNodes(hasText("Password required")).fetchSemanticsNodes().isEmpty() }
        assertEquals("Can't read this PDF.", ShadowToast.getTextOfLatestToast())
        rule.onNodeWithText("Incorrect password, try again.").assertDoesNotExist()
        rule.onNodeWithText("Choose PDF").assertExists()
        assertEquals("no decrypted copy is left", before, cacheFiles())
    }

    @Test
    fun `a wrong password handed over by the reader opens the prompt with its error`() {
        val source = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "handed-wrong-compress.pdf")
        rule.setContent { CompressView(initialUri = source, initialPassword = "wrong", onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Password required")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Incorrect password, try again.").assertExists()
    }

    @Test
    fun `a damaged protected file handed over with its password says it can't be read and goes back to choosing a file`() {
        val source = TestPdfs.write(context.cacheDir, TestPdfs.damagedProtected(), "handed-damaged-compress.pdf")
        rule.setContent { CompressView(initialUri = source, initialPassword = TestPdfs.USER_PASSWORD, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        rule.waitUntil(10_000) { ShadowToast.getTextOfLatestToast() == "Can't read this PDF." }
        rule.onNodeWithText("Choose PDF").assertExists()
        rule.onNodeWithText("Password required").assertDoesNotExist()
    }
}
