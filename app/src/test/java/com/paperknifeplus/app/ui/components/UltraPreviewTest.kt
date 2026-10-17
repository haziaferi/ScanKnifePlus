package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import coil.request.Options
import com.paperknifeplus.app.data.image.PdfImageLoader
import com.paperknifeplus.app.data.image.PdfPageKeyer
import com.paperknifeplus.app.data.image.PdfPageRequest
import com.paperknifeplus.app.testing.mainThreadAndroidComposeRule
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class UltraPreviewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun bitmapBytes(widthPt: Float, heightPt: Float): Long {
        val scale = highResScale(widthPt, heightPt)
        return (widthPt * scale).toLong() * (heightPt * scale).toLong() * 4
    }

    @Test
    fun `the sharp layer stays under the canvas limit and above the first render`() {
        val limit = 100L * 1024 * 1024
        for ((w, h) in listOf(595f to 842f, 612f to 792f, 842f to 1191f, 2384f to 3370f)) {
            assertTrue("$w x $h: ${bitmapBytes(w, h)} bytes", bitmapBytes(w, h) < limit)
            assertTrue("$w x $h", highResScale(w, h) >= 1.8f)
        }
        // A4 gets about 5x, a 48 MiB bitmap; small pages are capped at 12x.
        assertEquals(5.01f, highResScale(595f, 842f), 0.01f)
        assertEquals(12f, highResScale(100f, 100f))
        assertEquals(1.8f, highResScale(2384f, 3370f))
    }

    @Test
    fun `links on an unrotated page at the origin map as before`() {
        val area = mapLinkRect(72f, 700f, 172f, 720f, PageBox(0f, 0f, 612f, 792f, 0))
        assertEquals(72f / 612f, area.left, 1e-6f)
        assertEquals((792f - 720f) / 792f, area.top, 1e-6f)
        assertEquals(100f / 612f, area.width, 1e-6f)
        assertEquals(20f / 792f, area.height, 1e-6f)
    }

    @Test
    fun `links follow the page rotation`() {
        // A 100 x 50 pt link in the user-space lower-left corner of an A4 page.
        val corner = listOf(0f, 0f, 100f, 50f)
        fun map(rotation: Int) = mapLinkRect(corner[0], corner[1], corner[2], corner[3], PageBox(0f, 0f, 595f, 842f, rotation))
        // Turned 90 degrees clockwise, the lower-left corner shows at the top-left, 50 wide and 100 tall.
        assertEquals(LinkArea(0f, 0f, 50f / 842f, 100f / 595f), map(90))
        assertEquals(LinkArea((595f - 100f) / 595f, 0f, 100f / 595f, 50f / 842f), map(180))
        assertEquals(LinkArea((842f - 50f) / 842f, (595f - 100f) / 595f, 50f / 842f, 100f / 595f), map(270))
    }

    @Test
    fun `links are measured from the cropBox origin, also when it is smaller than the mediaBox`() {
        val cropped = PageBox(100f, 200f, 300f, 400f, 0)
        val area = mapLinkRect(100f, 550f, 250f, 600f, cropped)
        assertEquals(0f, area.left, 1e-6f)
        assertEquals(0f, area.top, 1e-6f)
        assertEquals(0.5f, area.width, 1e-6f)
        assertEquals(50f / 400f, area.height, 1e-6f)
    }

    @Test
    fun `only web, mail and phone links open`() {
        assertEquals("https://example.com/a?b=1", safeLinkUri("https://example.com/a?b=1"))
        assertEquals("http://example.com", safeLinkUri("  http://example.com "))
        assertEquals("https://Example.com/Path", safeLinkUri("HTTPS://Example.com/Path"))
        assertEquals("mailto:someone@example.com", safeLinkUri("MailTo:someone@example.com"))
        assertEquals("tel:+123", safeLinkUri("tel:+123"))
        for (rejected in listOf(
            "file:///sdcard/secret.pdf", "content://com.example/x", "intent://scan/#Intent;scheme=zxing;end", "javascript:alert(1)",
            "market://details?id=x", "myapp://open", "www.example.com", "", "   ", "://nothing"
        )) {
            assertNull(rejected, safeLinkUri(rejected))
        }
    }

    @Test
    fun `tools get the original file and the password it was unlocked with`() {
        val original = Uri.parse("content://docs/locked.pdf")
        val copy = Uri.parse("file:///cache/decrypted_1.pdf")
        assertEquals(original to null, ReaderSource(original).toolHandOff())
        val unlocked = ReaderSource(original).unlocked(copy, "secret")
        assertEquals(copy, unlocked.displayUri)
        assertEquals(original to "secret", unlocked.toolHandOff())
    }

    @Test
    fun `the loader reads page boxes and web links`() {
        val bytes = PDDocument.load(TestPdfs.inheritedAttributes(2)).use { doc ->
            val link = PDAnnotationLink().apply {
                rectangle = PDRectangle(10f, 20f, 30f, 40f)
                action = PDActionURI().apply { uri = "https://example.com" }
            }
            doc.getPage(1).annotations = listOf(link)
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val document = runBlocking { loadReaderDocument(context, TestPdfs.write(context.cacheDir, bytes, "reader.pdf")) }!!
        assertEquals(List(2) { PageBox(0f, 0f, 595f, 842f, 90) }, document.pages)
        assertEquals(1, document.links.size)
        assertEquals(1, document.links.single().pageIndex)
        assertEquals("https://example.com", document.links.single().url)
    }

    @Test
    fun `the loader fails cleanly on garbage and missing streams`() {
        assertNull(runBlocking { loadReaderDocument(context, TestPdfs.write(context.cacheDir, TestPdfs.garbage, "reader-garbage.pdf")) })
        assertNull(runBlocking { loadReaderDocument(context, TestPdfs.nullStreamUri()) })
    }
}

/** Tools opened from the preview with the password the file was unlocked with: they use it, and forget it for the next file. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolHandOffTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = mainThreadAndroidComposeRule<ComponentActivity>()

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

    private fun show(picked: Uri, content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides pickerReturning(picked), content = content)
    }

    private fun waitFor(text: String) = rule.waitUntil(30_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Whether a first-page thumbnail of [uri] at [scale] rendered with [password] is cached; Coil only loads what is drawn. */
    private fun thumbnailCached(uri: Uri, password: String?, scale: Float): Boolean {
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            root.draw(Canvas(Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
        }
        val key = PdfPageKeyer().key(PdfPageRequest(uri, 0, password, scale), Options(context))
        return PdfImageLoader.get(context).memoryCache!!.keys.any { it.key == key }
    }

    @Test
    fun `Merge unlocks a file handed over with its password`() {
        val locked = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked-merge.pdf")
        show(locked) { MergeView(initialUri = locked, initialPassword = TestPdfs.USER_PASSWORD, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor("Add more")
        rule.onNodeWithText("Locked").assertDoesNotExist()
    }

    @Test
    fun `Compress forgets the handed-over password after CHANGE`() {
        val locked = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked-compress.pdf")
        val plain = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "plain-compress.pdf")
        show(plain) { CompressView(initialUri = locked, initialPassword = TestPdfs.USER_PASSWORD, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor("2 pages")
        rule.onNodeWithText("Change").performClick()
        rule.onNodeWithText("Tap to choose a PDF").performClick()
        waitFor("1 pages")
        rule.waitUntil(30_000) { thumbnailCached(plain, null, 1.2f) }
        assertFalse(thumbnailCached(plain, TestPdfs.USER_PASSWORD, 1.2f))
    }

    @Test
    fun `Rearrange forgets the handed-over password after CHANGE`() {
        val locked = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked-rearrange.pdf")
        val plain = TestPdfs.write(context.cacheDir, TestPdfs.plain(1), "plain-rearrange.pdf")
        show(plain) { RearrangeView(initialUri = locked, initialPassword = TestPdfs.USER_PASSWORD, onBack = {}, onOpenPreview = { _, _, _ -> }) }
        waitFor("2 pages")
        rule.onNodeWithText("Change").performClick()
        rule.onNodeWithText("Tap to choose a PDF").performClick()
        waitFor("1 pages")
        rule.waitUntil(30_000) { thumbnailCached(plain, null, 0.6f) }
        assertFalse(thumbnailCached(plain, TestPdfs.USER_PASSWORD, 0.6f))
    }
}
