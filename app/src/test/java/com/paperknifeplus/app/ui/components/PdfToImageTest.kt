package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import coil.request.Options
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageKeyer
import com.paperknifeplus.app.data.image.PdfPageRequest
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfToImageTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /** Whether page [index] of [uri] at [scale] with [password] is cached; Coil only loads what is drawn, and Robolectric never draws. */
    private fun pageCached(uri: Uri, index: Int, password: String?, scale: Float): Boolean {
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            root.draw(Canvas(Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
        }
        val key = PdfPageKeyer().key(PdfPageRequest(uri, index, password, scale), Options(context))
        return PdfImageLoader.get(context).memoryCache!!.keys.any { it.key == key }
    }

    @Test
    fun `a document opens with every page selected`() {
        assertEquals(setOf(0, 1, 2), initialSelection(3))
        assertEquals(emptySet<Int>(), initialSelection(0))
    }

    @Test
    fun `the default format is one of the chips`() {
        assertEquals("JPG", IMAGE_FORMATS.first())
        assertTrue(IMAGE_FORMATS.containsAll(listOf("JPG", "PNG", "WebP")))
    }

    @Test
    fun `opened with a file, every page is selected and the format is JPG`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "three-images.pdf")
        rule.setContent { PdfToImageView(initialUri = uri, onBack = {}) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Convert 3 pages to JPG")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Convert 3 pages to JPG")).assertExists()
    }

    @Test
    fun `a file without pages says it can't be read and goes back to choosing a file`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(0), "empty-images.pdf")
        rule.setContent { PdfToImageView(initialUri = uri, onBack = {}) }
        rule.waitUntil(10_000) { ShadowToast.getTextOfLatestToast() == "Can't read this PDF." }
        rule.onNodeWithText("Choose PDF").assertExists()
        rule.onNode(hasText("Convert 0 pages", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `each page tile is a checkbox that says whether the page is exported`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(3), "three-tiles.pdf")
        rule.setContent { PdfToImageView(initialUri = uri, onBack = {}) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Convert 3 pages to JPG")).fetchSemanticsNodes().isNotEmpty() }
        val tile = rule.onNode(isToggleable() and hasContentDescription("Page 1"))
        tile.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertIsOn()
        // The test window is small: the file card and the pinned action leave the first row of tiles only partly on screen.
        tile.performSemanticsAction(SemanticsActions.OnClick)
        tile.assertIsOff()
        rule.onNode(hasText("Convert 2 pages to JPG")).assertExists()
    }

    @Test
    fun `unlocking a protected file parses it twice, the thumbnail and page grid reusing what the password check read`() {
        val source = TestPdfs.CountedSource(context, TestPdfs.userPassword(pages = 4), "counted-pdf2img.pdf")
        rule.setContent { PdfToImageView(initialUri = source.uri, onBack = {}) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Password required")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction()).performTextInput(TestPdfs.USER_PASSWORD)
        rule.onNodeWithText("Unlock").performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Convert 4 pages to JPG")).fetchSemanticsNodes().isNotEmpty() }

        rule.waitUntil(30_000) {
            pageCached(source.uri, 0, TestPdfs.USER_PASSWORD, THUMBNAIL_SCALE) && pageCached(source.uri, 0, TestPdfs.USER_PASSWORD, 0.6f)
        }
        // Once to find that it needs a password, once to check the password; as many as before the check told its outcomes apart.
        assertEquals(2, source.opens)
    }

    @Test
    fun `running out of memory while exporting shows the failure and removes the file the save created`() {
        val (output, created) = TestPdfs.createdDocument(context, "huge-pages.zip")
        // Opening the output runs out of memory, as drawing a page at 3 times its size can.
        Shadows.shadowOf(context.contentResolver).registerOutputStreamSupplier(output) { throw OutOfMemoryError("test") }
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.plain(2), "exported.pdf")
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides TestPdfs.savingTo(output)) {
                PdfToImageView(initialUri = uri, onBack = {})
            }
        }
        rule.waitUntil(10_000) { shown(CONVERT) }

        rule.onNodeWithText(CONVERT).performClick()
        // Reading the screen runs the main thread's queue, where the failure handler shows its message and goes back to the pages.
        rule.waitUntil(30_000) { shown(CONVERT) && ShadowToast.getTextOfLatestToast() == TestPdfs.OUT_OF_MEMORY }
        assertFalse("the created file is removed", created.exists())
    }

    private fun shown(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val CONVERT = "Convert 2 pages to JPG"
    }
}
