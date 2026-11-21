package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
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
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

/** Rotate on its screen: running out of memory while saving, and a protected file picked after another file's pages were turned. */
@RunWith(RobolectricTestRunner::class)
class RotateViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun shown(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `running out of memory while saving shows the failure and removes the file the save created`() {
        val (output, created) = TestPdfs.createdDocument(context, "huge-rotated.pdf")
        val source = TestPdfs.OutOfMemorySource(context, TestPdfs.plain(2), "huge-rotate.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(output)) {
                RotateView(initialUri = source.uri, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(10_000) { shown("0 / 2 pages modified") }

        source.runOutOfMemory()
        rule.onNodeWithText("Save PDF").performClick()
        // Reading the screen runs the main thread's queue, where the failure handler shows its message and goes back to the pages.
        rule.waitUntil(30_000) { shown("0 / 2 pages modified") && ShadowToast.getTextOfLatestToast() == TestPdfs.OUT_OF_MEMORY }
        assertFalse("the created file is removed", created.exists())
    }

    @Test
    fun `a protected file unlocked after Change starts with no page turned, and is saved so`() {
        val first = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "rotate-first.pdf")
        val locked = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "rotate-then-locked.pdf")
        val output = File(context.cacheDir, "rotate-locked-out.pdf").apply { delete() }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(Uri.fromFile(output), picked = locked)) {
                RotateView(initialUri = first, onBack = {}, onOpenPreview = { _, _, _ -> })
            }
        }
        rule.waitUntil(10_000) { shown("0 / 2 pages modified") }
        rule.onNodeWithText("Rotate all 90°").performClick()
        rule.waitUntil(10_000) { shown("2 / 2 pages modified") }

        rule.onNodeWithText("Change").performClick()
        rule.onNodeWithText("Choose PDF").performClick()
        rule.waitUntil(10_000) { shown("Password required") }
        rule.onNode(hasSetTextAction()).performTextInput(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()
        rule.waitUntil(30_000) { shown("Save PDF") }
        rule.onNodeWithText("0 / 2 pages modified").assertExists()

        rule.onNodeWithText("Save PDF").performClick()
        rule.waitUntil(30_000) { shown("Rotate complete") }
        PDDocument.load(output, TestPdfs.USER_PASSWORD).use { doc -> assertEquals(listOf(0, 0), doc.pages.map { it.rotation }) }
    }
}
