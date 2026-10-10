package com.haziaferi.scanknifeplus.scan.capture

import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The EXIF transform and the upright decode, with Robolectric's native graphics (the real Skia codecs). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageCodecTest {
    private val dir: File = Files.createTempDirectory("codec").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `the orientation matrix puts every corner of a 4x3 image where its EXIF orientation says`() {
        // Where the stored image's top-left, top-right, bottom-right and bottom-left corners land upright.
        val expected = mapOf(
            ExifInterface.ORIENTATION_UNDEFINED to listOf(0, 0, 4, 0, 4, 3, 0, 3),
            ExifInterface.ORIENTATION_NORMAL to listOf(0, 0, 4, 0, 4, 3, 0, 3),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to listOf(4, 0, 0, 0, 0, 3, 4, 3),
            ExifInterface.ORIENTATION_ROTATE_180 to listOf(4, 3, 0, 3, 0, 0, 4, 0),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to listOf(0, 3, 4, 3, 4, 0, 0, 0),
            ExifInterface.ORIENTATION_TRANSPOSE to listOf(0, 0, 0, 4, 3, 4, 3, 0),
            ExifInterface.ORIENTATION_ROTATE_90 to listOf(3, 0, 3, 4, 0, 4, 0, 0),
            ExifInterface.ORIENTATION_TRANSVERSE to listOf(3, 4, 3, 0, 0, 0, 0, 4),
            ExifInterface.ORIENTATION_ROTATE_270 to listOf(0, 4, 0, 0, 3, 0, 3, 4),
        )
        for ((orientation, corners) in expected) {
            val points = floatArrayOf(0f, 0f, 4f, 0f, 4f, 3f, 0f, 3f)
            ImageCodec.orientationMatrix(orientation, 4, 3).mapPoints(points)
            assertEquals("orientation $orientation", corners.map { it.toFloat() }, points.map { it + 0f }) // + 0f turns -0.0 into 0.0
        }
    }

    @Test
    fun `a landscape JPEG tagged to rotate 90 degrees decodes upright as portrait`() {
        val file = File(dir, "tagged.jpg")
        // Stored landscape, red on the left and blue on the right: turned clockwise, red is on top.
        val stored = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        for (x in 0 until 40) for (y in 0 until 30) stored.setPixel(x, y, if (x < 20) Color.RED else Color.BLUE)
        file.outputStream().use { stored.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()) }.saveAttributes()

        val upright = ImageCodec.decodeUpright(ImageSource.of(file))!!
        assertEquals(30, upright.width)
        assertEquals(40, upright.height)
        assertTrue(Color.red(upright.getPixel(15, 5)) > 200 && Color.blue(upright.getPixel(15, 5)) < 60)
        assertTrue(Color.blue(upright.getPixel(15, 35)) > 200 && Color.red(upright.getPixel(15, 35)) < 60)

        val fitted = ImageCodec.decodeUpright(ImageSource.of(file), maxEdge = 20)!!
        assertEquals(15, fitted.width)
        assertEquals(20, fitted.height)
    }

    @Test
    fun `bytes that are no image decode to null`() {
        val junk = File(dir, "junk.jpg").apply { writeText("not an image") }
        assertNull(ImageCodec.decodeUpright(ImageSource.of(junk)))
        assertNull(ImageCodec.decodeUpright(ImageSource.of(File(dir, "missing.jpg"))))
    }
}
