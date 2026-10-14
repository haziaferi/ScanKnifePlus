package com.paperknifeplus.app.ui.components

import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GrayJpegEncoderTest {
    companion object {
        /** A gray JPEG of a smooth diagonal ramp with a hard-edged square, at a size that is not a multiple of 8. */
        fun grayJpeg(width: Int = 37, height: Int = 21, quality: Int = 90): ByteArray = ByteArrayOutputStream().also { out ->
            GrayJpegEncoder.encode(width, height, quality, out) { y, row ->
                for (x in 0 until width) row[x] = expected(x, y, width, height).toByte()
            }
        }.toByteArray()

        fun expected(x: Int, y: Int, width: Int, height: Int): Int =
            if (x in 5..14 && y in 5..14) 0 else 40 + 180 * (x + y) / (width + height)
    }

    @Test
    fun `writes a baseline one-channel JPEG`() {
        val info = JpegHeadersTest.header(grayJpeg())!!
        assertTrue(info.baseline)
        assertEquals(1, info.components)
    }

    @Test
    fun `the platform decoder reads back the same picture`() {
        val bytes = grayJpeg()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertEquals(37, bitmap.width)
        assertEquals(21, bitmap.height)
        var worst = 0
        var total = 0L
        for (y in 0 until 21) for (x in 0 until 37) {
            val pixel = bitmap.getPixel(x, y)
            assertEquals(Color.red(pixel), Color.blue(pixel))
            val error = abs(Color.red(pixel) - expected(x, y, 37, 21))
            worst = maxOf(worst, error)
            total += error
        }
        assertTrue("mean error ${total / (37.0 * 21)}", total / (37.0 * 21) < 2.0)
        assertTrue("worst error $worst", worst < 24)
    }

    @Test
    fun `lower quality gives a smaller file`() {
        assertTrue(grayJpeg(quality = 30).size < grayJpeg(quality = 95).size)
    }
}
