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
        val stored = CaptureStore.store(ImageSource.of(src), null, File(dir, "page.jpg"), File(dir, "orig.jpg"))!!
        for (file in listOf(stored.page, stored.original!!)) {
            val image = ImageCodec.decodeScaled(ImageSource.of(file), 300)!!
            assertColor(Color.WHITE, image.rgbAt(0.05, 0.05), "${file.name} transparent corner")
            assertColor(Color.BLACK, image.rgbAt(0.5, 0.5), "${file.name} opaque centre")
        }
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
        // The sheet is 1800x3200 at full size: the photo decodes at 3000 px (2250x3000) so the sheet lands on the cap as 1350x2400.
        assertEquals(PageSize(1350, 2400), PageSize(stored.pageWidth, stored.pageHeight))
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
        // A uniquely named folder, so the test can never touch a real share copy.
        val shared = File(ScanFiles.newFolder(ScanFiles.shareDir(context), "capture-test-")!!, "photo.jpg")
        documentPhoto().copyTo(shared, overwrite = true)
        try {
            val source = ImageSource.of(context.contentResolver, ScanFiles.contentUri(context, shared))
            assertNotNull(CaptureStore.store(source, null, File(dir, "page.jpg"), null))
        } finally {
            shared.parentFile!!.deleteRecursively()
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

    @Test
    fun aFailedOriginalStillKeepsThePage() {
        val photo = documentPhoto()
        var opens = 0
        // The header takes two opens and the page decode one; the fourth open, for the original, fails.
        val source = ImageSource { if (++opens >= 4) throw java.io.IOException("gone") else photo.inputStream() }
        val orig = File(dir, "orig.jpg")
        val stored = CaptureStore.store(source, null, File(dir, "page.jpg"), orig)!!
        assertTrue(stored.page.exists())
        assertNull(stored.original)
        assertFalse(orig.exists())
    }

    @Test
    fun sixteenBitImagesAreNormalisedToEightBit() {
        val png = File(dir, "deep.png").apply { writeBytes(sixteenBitPng(64, 48, red = 0xFFFF, green = 0x8000, blue = 0)) }
        val raw = BitmapFactory.decodeFile(png.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        val rawConfig = raw.config
        raw.recycle()
        android.util.Log.i("CaptureStoreDeviceTest", "16-bit PNG decodes as $rawConfig")
        val image = ImageCodec.decodeScaled(ImageSource.of(png), 64)!!
        assertEquals(PageSize(64, 48), PageSize(image.width, image.height))
        assertColor(Color.rgb(255, 128, 0), image.rgbAt(0.5, 0.5), "16-bit colour (platform decoded it as $rawConfig)")
        assertNotNull(CaptureStore.store(ImageSource.of(png), null, File(dir, "page.jpg"), null))
    }

    @Test
    fun aWriteThatCannotCompleteLeavesTheDestinationAndNoTempFile() {
        val image = ImageCodec.decodeScaled(ImageSource.of(jpeg(quadrants(), "q.jpg")), 400)!!
        // A directory cannot be replaced by a file, so the final rename fails.
        val dest = File(dir, "taken").apply { mkdirs() }
        assertFalse(ImageCodec.writeJpeg(image, dest, 85))
        assertTrue(dest.isDirectory)
        assertFalse(File(dir, "taken.tmp").exists())
    }

    /** A minimal 16-bit-per-channel RGBA PNG of one colour, written by hand since Bitmap.compress only writes 8-bit PNGs. */
    private fun sixteenBitPng(width: Int, height: Int, red: Int, green: Int, blue: Int): ByteArray {
        fun chunk(out: java.io.ByteArrayOutputStream, type: String, data: ByteArray) {
            val d = java.io.DataOutputStream(out)
            d.writeInt(data.size)
            val typed = type.toByteArray(Charsets.US_ASCII) + data
            d.write(typed)
            d.writeInt(java.util.zip.CRC32().apply { update(typed) }.value.toInt())
        }
        val raw = java.io.ByteArrayOutputStream()
        val rows = java.io.DataOutputStream(raw)
        repeat(height) {
            rows.writeByte(0) // filter: none
            repeat(width) { rows.writeShort(red); rows.writeShort(green); rows.writeShort(blue); rows.writeShort(0xFFFF) }
        }
        val ihdr = java.io.ByteArrayOutputStream().also {
            java.io.DataOutputStream(it).apply { writeInt(width); writeInt(height); writeByte(16); writeByte(6); writeByte(0); writeByte(0); writeByte(0) }
        }.toByteArray()
        val compressed = java.io.ByteArrayOutputStream().also { o -> java.util.zip.DeflaterOutputStream(o).use { it.write(raw.toByteArray()) } }.toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", compressed)
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }
}
