package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
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
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    @Test
    fun `opened with a file, Delete goes straight to page selection`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "three.pdf")
        rule.setContent { DeleteView(initialUri = uri, onBack = {}, onOpenPreview = { _, _, _ -> }) }

        rule.waitUntil(10_000) { rule.onAllNodes(hasText("MARKED FOR DELETION", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("0 / 3 MARKED FOR DELETION").assertExists()
        rule.onNodeWithText("Tap to enter file").assertDoesNotExist()
    }

    @Test
    fun `opened with an encrypted file and its password, Delete unlocks it without asking`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked.pdf")
        rule.setContent { DeleteView(initialUri = uri, initialPassword = TestPdfs.USER_PASSWORD, onBack = {}, onOpenPreview = { _, _, _ -> }) }

        rule.waitUntil(10_000) { rule.onAllNodes(hasText("MARKED FOR DELETION", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("0 / 2 MARKED FOR DELETION").assertExists()
        rule.onNodeWithText("Password Required").assertDoesNotExist()
    }
}
