package com.paperknifeplus.app.ui.components

import android.content.Context
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        // A4 gets a real high-resolution layer, about 5x, instead of the old 12x (288 MB).
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
