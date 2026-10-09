package com.haziaferi.scanknifeplus.scan.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scanner.cv.PageSize
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.cv.RgbaImage
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Decoding, EXIF orientation, flattening, cropping and writing on a real device's codecs (run with `./gradlew connectedDebugAndroidTest`). */
@RunWith(AndroidJUnit4::class)
class CaptureStoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "capture-test").apply { mkdirs() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A 400x300 image whose quadrants are red (top-left), green (top-right), blue (bottom-left) and white (bottom-right). */
    private fun quadrants(): Bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
        val c = Canvas(this)
        val p = Paint()
        for ((color, rect) in listOf(
            Color.RED to floatArrayOf(0f, 0f, 200f, 150f),
            Color.GREEN to floatArrayOf(200f, 0f, 400f, 150f),
            Color.BLUE to floatArrayOf(0f, 150f, 200f, 300f),
            Color.WHITE to floatArrayOf(200f, 150f, 400f, 300f),
        )) {
            p.color = color
            c.drawRect(rect[0], rect[1], rect[2], rect[3], p)
        }
    }

    private fun jpeg(bitmap: Bitmap, name: String, orientation: Int = ExifInterface.ORIENTATION_NORMAL): File {
        val f = File(dir, name)
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            ExifInterface(f).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
        }
        return f
    }

    /** RGB at a fraction of the image's width and height. */
    private fun RgbaImage.rgbAt(fx: Double, fy: Double): Triple<Int, Int, Int> {
        val i = ((fy * height).toInt() * width + (fx * width).toInt()) * 4
        return Triple(pixels[i].toInt() and 0xFF, pixels[i + 1].toInt() and 0xFF, pixels[i + 2].toInt() and 0xFF)
    }

    private fun assertColor(expected: Int, actual: Triple<Int, Int, Int>, label: String) {
        val e = Triple(Color.red(expected), Color.green(expected), Color.blue(expected))
        assertTrue("$label: expected $e, got $actual", abs(e.first - actual.first) < 40 && abs(e.second - actual.second) < 40 && abs(e.third - actual.third) < 40)
    }

    @Test
    fun everyExifOrientationDecodesUpright() {
        // Colour expected at the decoded image's top-left and top-right corners for each EXIF orientation of the quadrant image.
        val cases = listOf(
            Triple(ExifInterface.ORIENTATION_NORMAL, Color.RED, Color.GREEN),
            Triple(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, Color.GREEN, Color.RED),
            Triple(ExifInterface.ORIENTATION_ROTATE_180, Color.WHITE, Color.BLUE),
            Triple(ExifInterface.ORIENTATION_FLIP_VERTICAL, Color.BLUE, Color.WHITE),
            Triple(ExifInterface.ORIENTATION_TRANSPOSE, Color.RED, Color.BLUE),
            Triple(ExifInterface.ORIENTATION_ROTATE_90, Color.BLUE, Color.RED),
            Triple(ExifInterface.ORIENTATION_TRANSVERSE, Color.WHITE, Color.GREEN),
            Triple(ExifInterface.ORIENTATION_ROTATE_270, Color.GREEN, Color.WHITE),
        )
        val bitmap = quadrants()
        for ((orientation, topLeft, topRight) in cases) {
            val source = ImageSource.of(jpeg(bitmap, "o$orientation.jpg", orientation))
            val swapped = orientation in listOf(5, 6, 7, 8)
            assertEquals("orientation $orientation size", if (swapped) PageSize(300, 400) else PageSize(400, 300), ImageCodec.orientedSize(source))
            val image = ImageCodec.decodeScaled(source, 200)!!
            assertEquals("orientation $orientation scaled", if (swapped) PageSize(150, 200) else PageSize(200, 150), PageSize(image.width, image.height))
            assertColor(topLeft, image.rgbAt(0.1, 0.1), "orientation $orientation top-left")
            assertColor(topRight, image.rgbAt(0.9, 0.1), "orientation $orientation top-right")
        }
    }

    @Test
    fun decodingNeverUpscales() {
        val image = ImageCodec.decodeScaled(ImageSource.of(jpeg(quadrants(), "small.jpg")), 5000)!!
        assertEquals(PageSize(400, 300), PageSize(image.width, image.height))
    }

    @Test
    fun transparencyIsFlattenedOntoWhite() {
        val png = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawRect(100f, 100f, 200f, 200f, Paint().apply { color = Color.BLACK })
        }
        val src = File(dir, "logo.png").apply { outputStream().use { png.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val stored = CaptureStore.store(ImageSource.of(src), null, File(dir, "page.jpg"), null)!!
        val page = ImageCodec.decodeScaled(ImageSource.of(stored.page), 300)!!
        assertColor(Color.WHITE, page.rgbAt(0.05, 0.05), "transparent corner")
        assertColor(Color.BLACK, page.rgbAt(0.5, 0.5), "opaque centre")
    }

    /** A dark 3000x4000 photo with a bright sheet covering the normalized rectangle 0.2..0.8 x 0.1..0.9. */
    private fun documentPhoto(): File {
        val bitmap = Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(40, 40, 40))
            Canvas(this).drawRect(600f, 400f, 2400f, 3600f, Paint().apply { color = Color.rgb(230, 230, 230) })
        }
        return jpeg(bitmap, "photo.jpg").also { bitmap.recycle() }
    }

    @Test
    fun aQuadIsCroppedToAPageAtTheCap() {
        val quad = Quad(Pt(0.2, 0.1), Pt(0.8, 0.1), Pt(0.8, 0.9), Pt(0.2, 0.9))
        val stored = CaptureStore.store(ImageSource.of(documentPhoto()), quad, File(dir, "page.jpg"), File(dir, "orig.jpg"))!!
        assertTrue(stored.cropped)
        // The sheet is 1800x3200 at full size: decoded so it lands on the 2400 cap, giving a 1350x2400 page.
        assertEquals(2400, maxOf(stored.pageWidth, stored.pageHeight))
        assertTrue("page is portrait", stored.pageHeight > stored.pageWidth)
        val page = ImageCodec.decodeScaled(ImageSource.of(stored.page), 4000)!!
        assertEquals(PageSize(stored.pageWidth, stored.pageHeight), PageSize(page.width, page.height))
        for ((fx, fy) in listOf(0.05 to 0.05, 0.5 to 0.5, 0.95 to 0.95)) assertColor(Color.rgb(230, 230, 230), page.rgbAt(fx, fy), "sheet at $fx,$fy")

        // The original is the whole photo fitted to 3200 (3000x4000 -> 2400x3200).
        val original = ImageCodec.orientedSize(ImageSource.of(stored.original!!))
        assertEquals(PageSize(2400, 3200), original)
    }

    @Test
    fun withoutAQuadThePageIsTheWholeImageFittedToTheCap() {
        val stored = CaptureStore.store(ImageSource.of(documentPhoto()), null, File(dir, "page.jpg"), null)!!
        assertFalse(stored.cropped)
        assertNull(stored.original)
        assertEquals(PageSize(1800, 2400), PageSize(stored.pageWidth, stored.pageHeight))
        assertEquals(PageSize(1800, 2400), ImageCodec.orientedSize(ImageSource.of(stored.page)))
    }

    @Test
    fun contentUrisWorkAsSources() {
        val inLibrary = File(ScanFiles.libraryDir(context), "capture-test/photo.jpg").apply { parentFile!!.mkdirs() }
        documentPhoto().copyTo(inLibrary, overwrite = true)
        try {
            val source = ImageSource.of(context.contentResolver, ScanFiles.contentUri(context, inLibrary))
            assertNotNull(CaptureStore.store(source, null, File(dir, "page.jpg"), null))
        } finally {
            inLibrary.parentFile!!.deleteRecursively()
        }
    }

    @Test
    fun undecodableBytesStoreNothing() {
        val junk = File(dir, "junk.jpg").apply { writeBytes(ByteArray(1000) { it.toByte() }) }
        val page = File(dir, "page.jpg")
        val orig = File(dir, "orig.jpg")
        assertNull(CaptureStore.store(ImageSource.of(junk), null, page, orig))
        assertFalse(page.exists())
        assertFalse(orig.exists())
        assertTrue("no temp files left", dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun writingReplacesAnExistingPageAndLeavesNoTempFile() {
        val dest = File(dir, "page.jpg").apply { writeText("old") }
        val image = ImageCodec.decodeScaled(ImageSource.of(jpeg(quadrants(), "q.jpg")), 400)!!
        assertTrue(ImageCodec.writeJpeg(image, dest, 85))
        assertEquals(PageSize(400, 300), ImageCodec.orientedSize(ImageSource.of(dest)))
        assertFalse(File(dir, "page.jpg.tmp").exists())
        val decoded = BitmapFactory.decodeFile(dest.path)
        assertNotNull(decoded)
    }
}
