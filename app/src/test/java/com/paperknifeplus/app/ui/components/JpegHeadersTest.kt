package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JpegHeadersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    companion object {
        /** A 40x20 colour JPEG from the platform encoder (baseline, 3 components), with EXIF from [exif] if given. */
        fun colourJpeg(dir: File, name: String, exif: (ExifInterface.() -> Unit)? = null): ByteArray {
            val file = File(dir, name)
            val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 30, 60)) }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            if (exif != null) ExifInterface(file).apply(exif).saveAttributes()
            return file.readBytes()
        }

        /** [jpeg] with an XMP segment holding [xmp] inserted after the SOI marker. */
        fun withXmp(jpeg: ByteArray, xmp: String): ByteArray {
            val payload = "http://ns.adobe.com/xap/1.0/\u0000$xmp".toByteArray(Charsets.ISO_8859_1)
            return ByteArrayOutputStream().apply {
                write(jpeg, 0, 2)
                write(0xFF); write(0xE1); write((payload.size + 2) shr 8); write((payload.size + 2) and 0xFF)
                write(payload)
                write(jpeg, 2, jpeg.size - 2)
            }.toByteArray()
        }

        /** The bytes from the start-of-scan marker to the end: the compressed image itself. */
        fun scanData(jpeg: ByteArray): ByteArray {
            for (i in 0 until jpeg.size - 1) if (jpeg[i] == 0xFF.toByte() && jpeg[i + 1] == 0xDA.toByte()) return jpeg.copyOfRange(i, jpeg.size)
            error("No start of scan")
        }

        internal fun header(jpeg: ByteArray): JpegHeader? = JpegHeader.read(jpeg.inputStream())

        /** [jpeg] as Image to PDF copies it: header read from a stream, then the stream's rest. */
        fun withoutGps(jpeg: ByteArray): ByteArray {
            val input = jpeg.inputStream()
            val header = JpegHeader.read(input)!!
            return ByteArrayOutputStream().also { header.copyWithoutGps(input, it) }.toByteArray()
        }

        fun ByteArray.contains(text: String) = String(this, Charsets.ISO_8859_1).contains(text)
    }

    @Test
    fun `reads baseline, components and orientation`() {
        val upright = header(colourJpeg(context.cacheDir, "a.jpg"))!!
        assertTrue(upright.baseline)
        assertEquals(3, upright.components)
        assertEquals(1, upright.orientation)
        assertEquals(40, upright.width)
        assertEquals(20, upright.height)

        val turned = header(colourJpeg(context.cacheDir, "b.jpg") { setAttribute(ExifInterface.TAG_ORIENTATION, "6") })!!
        assertEquals(6, turned.orientation)
    }

    @Test
    fun `not a JPEG gives no info`() {
        assertNull(header("not a jpeg".toByteArray()))
        assertNull(header(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x7F, 0x00)))
    }

    @Test
    fun `an EXIF block with GPS is removed and the image data is untouched`() {
        val jpeg = colourJpeg(context.cacheDir, "gps.jpg") {
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            setLatLong(52.5163, 13.3777)
        }
        assertTrue(jpeg.contains("Exif"))
        val stripped = withoutGps(jpeg)
        assertFalse(stripped.contains("Exif"))
        assertArrayEquals(scanData(jpeg), scanData(stripped))
        assertNotNull(BitmapFactory.decodeByteArray(stripped, 0, stripped.size))
    }

    @Test
    fun `EXIF without GPS is kept, so the bytes stay the same`() {
        val jpeg = colourJpeg(context.cacheDir, "nogps.jpg") { setAttribute(ExifInterface.TAG_ORIENTATION, "3") }
        assertArrayEquals(jpeg, withoutGps(jpeg))
    }

    @Test
    fun `XMP naming GPS properties is removed, other XMP kept`() {
        val plain = colourJpeg(context.cacheDir, "xmp.jpg")
        val located = withXmp(plain, "<rdf:Description exif:GPSLatitude=\"52,31.0N\"/>")
        val stripped = withoutGps(located)
        assertFalse(stripped.contains("GPSLatitude"))
        assertArrayEquals(plain, stripped)

        val harmless = withXmp(plain, "<rdf:Description xmp:Rating=\"5\"/>")
        assertArrayEquals(harmless, withoutGps(harmless))
    }

    @Test
    fun `an EXIF block too broken to read is removed`() {
        val plain = colourJpeg(context.cacheDir, "broken.jpg")
        val payload = "Exif\u0000\u0000XX".toByteArray(Charsets.ISO_8859_1)
        val broken = ByteArrayOutputStream().apply {
            write(plain, 0, 2)
            write(0xFF); write(0xE1); write(0); write(payload.size + 2)
            write(payload)
            write(plain, 2, plain.size - 2)
        }.toByteArray()
        assertArrayEquals(plain, withoutGps(broken))
    }
}
