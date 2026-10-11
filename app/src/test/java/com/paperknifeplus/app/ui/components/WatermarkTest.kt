package com.paperknifeplus.app.ui.components

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap

@RunWith(RobolectricTestRunner::class)
class WatermarkTest {
    @Before
    fun setUp() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    private fun watermark(pageIndex: Int) = PlacedWatermark(
        pageIndex = pageIndex,
        bitmap = Bitmap.createBitmap(40, 16, Bitmap.Config.ARGB_8888),
        normalizedX = 0.5f,
        normalizedY = 0.5f,
        normalizedWidth = 0.5f,
        rotation = 0f,
        opacity = 0.5f
    )

    /** The distinct image streams the pages of [doc] draw, compared by identity. */
    private fun imageStreams(doc: PDDocument): Set<COSBase> {
        val streams = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        for (page in doc.pages) {
            val resources = page.resources ?: continue
            for (name in resources.xObjectNames) {
                (resources.getXObject(name) as? PDImageXObject)?.let { streams.add(it.cosObject) }
            }
        }
        return streams
    }

    @Test
    fun `a watermark on every page embeds its image once`() {
        PDDocument.load(TestPdfs.plain(3)).use { doc ->
            applyWatermarks(doc, listOf(watermark(pageIndex = -1)))
            assertEquals(1, imageStreams(doc).size)
            doc.pages.forEach { page -> assertEquals(1, page.resources.xObjectNames.count()) }

            val saved = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
            PDDocument.load(saved).use { reloaded ->
                assertEquals(1, imageStreams(reloaded).size)
                reloaded.pages.forEach { page -> assertEquals(1, page.resources.xObjectNames.count()) }
            }
        }
    }

    @Test
    fun `separate watermarks keep separate images and only their pages`() {
        PDDocument.load(TestPdfs.plain(3)).use { doc ->
            applyWatermarks(doc, listOf(watermark(pageIndex = 0), watermark(pageIndex = 2), watermark(pageIndex = 7)))
            assertEquals(2, imageStreams(doc).size)
            assertEquals(listOf(1, 0, 1), doc.pages.map { it.resources?.xObjectNames?.count() ?: 0 })
        }
    }

    @Test
    fun `text bitmaps keep their width for short text and grow for long text`() {
        assertEquals(1000, textBitmapWidth(0f))
        assertEquals(1000, textBitmapWidth(600f))
        assertEquals(1000, textBitmapWidth(920f))
        assertEquals(1081, textBitmapWidth(1000.5f))
        assertEquals(2080, textBitmapWidth(2000f))
    }

    @Test
    fun `a text bitmap is as wide as textBitmapWidth says`() {
        // Legacy Robolectric graphics measures every text as 0 wide, so this pins the short-text size the templates use.
        val bitmap = createTextBitmap("DRAFT", androidx.compose.ui.graphics.Color.Gray)
        assertEquals(1000, bitmap.width)
        assertEquals(400, bitmap.height)
    }
}
