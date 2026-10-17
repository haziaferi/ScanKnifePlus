package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.mainThreadComposeRule
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeleteViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = mainThreadComposeRule()

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
}
