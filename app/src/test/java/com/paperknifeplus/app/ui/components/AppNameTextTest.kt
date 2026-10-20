package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Protect and Repair screens name this app, not upstream PaperKnife+. */
@RunWith(RobolectricTestRunner::class)
class AppNameTextTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun awaitText(text: String) =
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun `Protect warns in the app's name`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(), "protect.pdf")
        rule.setContent { ProtectView(initialUri = uri, onBack = {}, onOpenPreview = { _, _, _ -> }) }

        awaitText("cannot recover lost passwords")
        rule.onNodeWithText("ScanKnife+ cannot recover lost passwords.").assertExists()
        rule.onNodeWithText("PaperKnife", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Repair describes itself in the app's name`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(), "repair.pdf")
        rule.setContent { RepairView(initialUri = uri, onBack = {}, onOpenPreview = { _, _, _ -> }) }

        awaitText("will rebuild the PDF index")
        rule.onNodeWithText("ScanKnife+ will rebuild the PDF index", substring = true).assertExists()
        rule.onNodeWithText("PaperKnife", substring = true).assertDoesNotExist()
    }
}
