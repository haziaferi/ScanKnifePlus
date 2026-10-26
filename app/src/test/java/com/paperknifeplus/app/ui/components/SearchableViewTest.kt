package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.scan.export.FakeOcrEngine
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Make PDF searchable end to end on its screen, with the fake engine and page images. */
@RunWith(RobolectricTestRunner::class)
class SearchableViewTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engine = FakeOcrEngine()
    private val renders = FakeRenders()
    private val output = File(context.cacheDir, "searchable-view-out.pdf")
    private var suggested: Any? = null

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        SessionManager.clearHistory()
        output.delete()
    }

    private val picker = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                suggested = input
                dispatchResult(requestCode, Uri.fromFile(output))
            }
        }
    }

    private val input = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "scan.pdf")

    private fun show() {
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                SearchableView(initialUri = input, onBack = {}, onOpenPreview = { _, _, _ -> }, openEngine = { engine }, renders = { _, _, _ -> renders })
            }
        }
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Make searchable")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `a PDF is made searchable and saved under the tool's name`() {
        show()
        rule.onNodeWithText("Redo pages that already have text").assertExists()
        rule.onNodeWithText("Make searchable").performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("PDF made searchable")).fetchSemanticsNodes().isNotEmpty() }

        // The source's name as its provider reports it, with the suffix the other tools give their outputs.
        assertEquals(pdfBaseName(getUriDetails(context, input).name) + "-searchable.pdf", suggested)
        rule.onNodeWithText("2 pages read.").assertExists()
        val text = PDDocument.load(output).use { PDFTextStripper().getText(it) }
        assertTrue(text, "page 1" in text && "page 2" in text)
        assertEquals("searchable", SessionManager.history.first().toolId)
        assertEquals(1, engine.closes)
    }

    @Test
    fun `cancel stops reading, saves nothing and goes back to the options`() {
        engine.onRead = {
            val end = System.currentTimeMillis() + 10_000
            while (engine.cancels == 0 && System.currentTimeMillis() < end) Thread.sleep(10)
        }
        show()
        rule.onNodeWithText("Make searchable").performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Reading page 1 of 2…")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Cancel").performClick()
        rule.waitUntil(60_000) { rule.onAllNodes(hasText("Make searchable")).fetchSemanticsNodes().isNotEmpty() && engine.closes == 1 }

        assertEquals(1, engine.cancels)
        assertEquals(1, engine.reads.size)
        assertFalse(output.exists())
        assertTrue(SessionManager.history.isEmpty())
    }
}
