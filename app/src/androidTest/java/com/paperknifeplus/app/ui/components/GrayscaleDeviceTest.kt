package com.paperknifeplus.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What Grayscale relies on from the platform: its PDF renderer for fallback pages and its JPEG decoder for the gray JPEG encoder. */
@RunWith(AndroidJUnit4::class)
class GrayscaleDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun file(name: String) = File(context.cacheDir, "$name-${System.nanoTime()}.pdf")

    private fun ints(vararg values: Int) = COSArray().apply { values.forEach { add(COSInteger.get(it.toLong())) } }

    /**
     * A Letter page turned by /Rotate 90 with black over the lower-left quarter of its unrotated content, plus a red square annotation over
     * its unrotated top-right; [extra] adds to the page.
     */
    private fun turnedPage(doc: PDDocument, content: String = "0 g 0 0 306 396 re f", extra: (PDPage) -> Unit = {}): PDPage {
        val page = PDPage(PDRectangle.LETTER)
        page.rotation = 90
        page.setContents(PDStream(doc, content.byteInputStream()))
        val appearance = doc.document.createCOSStream().apply {
            createOutputStream().use { it.write("1 0 0 rg 0 0 200 280 re f".toByteArray()) }
            setItem(COSName.TYPE, COSName.XOBJECT)
            setItem(COSName.SUBTYPE, COSName.FORM)
            setItem(COSName.BBOX, ints(0, 0, 200, 280))
        }
        val square = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, COSName.getPDFName("Square"))
            setItem(COSName.RECT, ints(400, 500, 600, 780))
            setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, appearance) })
        }
        page.cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(square) })
        extra(page)
        doc.addPage(page)
        return page
    }

    private fun render(file: File, width: Int, height: Int): Bitmap =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            renderer.openPage(0).use { page ->
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }

    private fun assertNear(expected: Int, pixel: Int, what: String) {
        assertTrue("$what: ${Integer.toHexString(pixel)}", abs(Color.red(pixel) - expected) <= 8 && abs(Color.blue(pixel) - expected) <= 8)
    }

    @Test
    fun theRasterizerDrawsTheTurnedPageUprightAndLeavesAnnotationsOut() {
        val source = file("turned")
        PDDocument().use { doc ->
            turnedPage(doc)
            doc.save(source)
        }
        NativePageRasterizer(context, Uri.fromFile(source), null).use { rasterizer ->
            val bitmap = rasterizer.render(0, 1650, 1275)
            assertEquals(1650, bitmap.width)
            assertEquals(1275, bitmap.height)
            // /Rotate 90 turns the unrotated lower-left quarter into the top-left of the shown page.
            assertNear(0, bitmap.getPixel(200, 200), "top left")
            assertNear(255, bitmap.getPixel(1300, 300), "top right")
            // Where the square annotation would be: the platform renderer draws page content only.
            assertEquals(Color.WHITE, bitmap.getPixel(1300, 1000))
            bitmap.recycle()
        }
    }

    @Test
    fun aFallbackPageShowsUprightAndGrayWithItsAnnotationKept() {
        val source = file("colour-key")
        PDDocument().use { doc ->
            // A colour-key masked RGB image is something the converter does not map, so the page falls back.
            val red = doc.document.createCOSStream().apply {
                createOutputStream().use { it.write(byteArrayOf(-1, 0, 0)) }
                setItem(COSName.TYPE, COSName.XOBJECT)
                setItem(COSName.SUBTYPE, COSName.IMAGE)
                setInt(COSName.WIDTH, 1)
                setInt(COSName.HEIGHT, 1)
                setInt(COSName.BITS_PER_COMPONENT, 8)
                setItem(COSName.COLORSPACE, COSName.DEVICERGB)
                setItem(COSName.MASK, ints(0, 0, 0, 0, 0, 0))
            }
            turnedPage(doc, "q 306 0 0 396 0 0 cm /Im0 Do Q") { page ->
                page.resources = PDResources(COSDictionary().apply {
                    setItem(COSName.XOBJECT, COSDictionary().apply { setItem(COSName.getPDFName("Im0"), red) })
                })
            }
            doc.save(source)
        }
        val output = file("gray")
        PDDocument.load(source).use { doc ->
            val fallbacks = NativePageRasterizer(context, Uri.fromFile(source), null).use { GrayscaleConverter(doc, it).convert() }
            assertEquals(1, fallbacks)
            doc.save(output)
        }
        PDDocument.load(output).use { doc ->
            assertEquals(90, doc.getPage(0).rotation)
            assertEquals(1, doc.getPage(0).annotations.size)
        }
        val shown = render(output, 1650, 1275)
        assertNear(54, shown.getPixel(200, 200), "red quarter, now gray")
        assertNear(255, shown.getPixel(1300, 300), "blank area")
        shown.recycle()
    }

    @Test
    fun thePlatformDecoderReadsTheGrayJpegEncoder() {
        val width = 37
        val height = 21
        fun expected(x: Int, y: Int) = if (x in 5..14 && y in 5..14) 0 else 40 + 180 * (x + y) / (width + height)
        val bytes = ByteArrayOutputStream().also { out ->
            GrayJpegEncoder.encode(width, height, 90, out) { y, row -> for (x in 0 until width) row[x] = expected(x, y).toByte() }
        }.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        var total = 0L
        for (y in 0 until height) for (x in 0 until width) {
            val pixel = bitmap.getPixel(x, y)
            assertEquals(Color.red(pixel), Color.green(pixel))
            total += abs(Color.red(pixel) - expected(x, y))
        }
        assertTrue("mean error ${total / (width * height.toDouble())}", total / (width * height.toDouble()) < 2.0)
    }
}
