package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.ui.components.JpegHeadersTest.Companion.colourJpeg
import com.paperknifeplus.app.ui.components.JpegHeadersTest.Companion.scanData
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageToPdfTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val output = File(context.cacheDir, "images.pdf")

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun file(name: String, bytes: ByteArray): Uri = Uri.fromFile(File(context.cacheDir, name).apply { writeBytes(bytes) })

    /** A 40x20 colour JPEG with EXIF [orientation], and a location if [gps]. */
    private fun jpeg(name: String, orientation: Int, gps: Boolean = false): ByteArray = colourJpeg(context.cacheDir, name) {
        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        if (gps) setLatLong(52.5163, 13.3777)
    }

    private fun png(name: String): Uri {
        val bitmap = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return file(name, ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
    }

    private fun garbage(name: String): Uri = file(name, "not an image".toByteArray())

    private fun convert(images: List<Uri>, pageSize: String = "Fit"): Int =
        runBlocking { convertImagesToPdf(context, images, Uri.fromFile(output), pageSize) { _, _ -> } }

    private fun image(page: PDPage): PDImageXObject = page.resources.getXObject(page.resources.xObjectNames.single()) as PDImageXObject

    private fun rawBytes(image: PDImageXObject) = image.cosObject.createRawInputStream().use { it.readBytes() }

    @Test
    fun `an upright photo is embedded byte for byte on a page sized at 300 DPI`() {
        val bytes = jpeg("upright.jpg", ExifInterface.ORIENTATION_NORMAL)
        assertEquals(1, convert(listOf(file("upright.jpg", bytes))))
        PDDocument.load(output).use { doc ->
            val page = doc.getPage(0)
            assertEquals(40 * 72f / 300, page.mediaBox.width, 0.001f)
            assertEquals(20 * 72f / 300, page.mediaBox.height, 0.001f)
            assertEquals(0, page.rotation)
            assertArrayEquals(bytes, rawBytes(image(page)))
        }
    }

    @Test
    fun `a photo tagged to turn keeps its bytes and the page turns instead`() {
        val bytes = jpeg("photo.jpg", ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(1, convert(listOf(file("photo.jpg", bytes))))
        PDDocument.load(output).use { doc ->
            val page = doc.getPage(0)
            assertEquals(90, page.rotation)
            assertEquals(40 * 72f / 300, page.mediaBox.width, 0.001f)
            assertEquals(20 * 72f / 300, page.mediaBox.height, 0.001f)
            assertArrayEquals(bytes, rawBytes(image(page)))
        }
    }

    @Test
    fun `on A4 a turned photo gets a landscape media box, so the turned page is portrait A4`() {
        convert(listOf(file("a4.jpg", jpeg("a4.jpg", ExifInterface.ORIENTATION_ROTATE_270))), pageSize = "A4")
        PDDocument.load(output).use { doc ->
            val page = doc.getPage(0)
            assertEquals(270, page.rotation)
            assertEquals(PDRectangle.A4.height, page.mediaBox.width)
            assertEquals(PDRectangle.A4.width, page.mediaBox.height)
        }
    }

    @Test
    fun `the location is removed from a passed-through photo without touching the image data`() {
        val bytes = jpeg("gps.jpg", ExifInterface.ORIENTATION_ROTATE_180, gps = true)
        convert(listOf(file("gps.jpg", bytes)))
        PDDocument.load(output).use { doc ->
            val page = doc.getPage(0)
            assertEquals(180, page.rotation)
            val embedded = rawBytes(image(page))
            assertNotEquals(bytes.size, embedded.size)
            assertFalse(String(embedded, Charsets.ISO_8859_1).contains("Exif"))
            assertArrayEquals(scanData(bytes), scanData(embedded))
        }
    }

    @Test
    fun `a mirrored photo is decoded upright and re-encoded`() {
        val bytes = jpeg("mirror.jpg", ExifInterface.ORIENTATION_TRANSPOSE)
        convert(listOf(file("mirror.jpg", bytes)))
        PDDocument.load(output).use { doc ->
            val page = doc.getPage(0)
            assertEquals(0, page.rotation)
            val embedded = image(page)
            assertEquals(20, embedded.width)
            assertEquals(40, embedded.height)
            assertEquals(COSName.DCT_DECODE, embedded.cosObject.getDictionaryObject(COSName.FILTER))
            assertFalse(bytes.contentEquals(rawBytes(embedded)))
        }
    }

    @Test
    fun `a gray JPEG is re-encoded rather than passed through as RGB`() {
        val gray = GrayJpegEncoderTest.grayJpeg()
        convert(listOf(file("gray.jpg", gray)))
        PDDocument.load(output).use { doc ->
            val embedded = rawBytes(image(doc.getPage(0)))
            assertEquals(3, JpegHeadersTest.header(embedded)!!.components)
        }
    }

    @Test
    fun `a PNG is stored losslessly`() {
        convert(listOf(png("shot.png")))
        PDDocument.load(output).use { doc ->
            val page = doc.getPage(0)
            assertEquals(600 * 72f / 300, page.mediaBox.width, 0.001f)
            val embedded = image(page)
            assertEquals(COSName.FLATE_DECODE, embedded.cosObject.getDictionaryObject(COSName.FILTER))
            assertEquals(Color.GREEN, embedded.image.getPixel(300, 150))
        }
    }

    @Test
    fun `an image that cannot be decoded is skipped and not counted`() {
        val a = file("a.jpg", jpeg("a.jpg", ExifInterface.ORIENTATION_NORMAL))
        val b = file("b.jpg", jpeg("b.jpg", ExifInterface.ORIENTATION_NORMAL))
        assertEquals(2, convert(listOf(a, garbage("broken.jpg"), b)))
        PDDocument.load(output).use { assertEquals(2, it.numberOfPages) }
    }

    @Test
    fun `no decodable image is an error and writes nothing`() {
        assertThrows(IOException::class.java) { convert(listOf(garbage("x.jpg"), garbage("y.jpg"))) }
        assertFalse(output.exists())
    }
}
