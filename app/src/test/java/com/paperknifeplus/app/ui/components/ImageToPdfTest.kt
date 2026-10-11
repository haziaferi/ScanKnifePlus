package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageToPdfTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val output = File(context.cacheDir, "images.pdf")

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /** A landscape 60x30 JPEG whose EXIF says to show it rotated 90 degrees, as phone cameras store portrait shots. */
    private fun rotatedJpeg(name: String): Uri {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(60, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString()) }.saveAttributes()
        return Uri.fromFile(file)
    }

    private fun garbage(name: String): Uri = Uri.fromFile(File(context.cacheDir, name).apply { writeText("not an image") })

    private fun convert(images: List<Uri>): Int = runBlocking { convertImagesToPdf(context, images, Uri.fromFile(output), "Fit") { _, _ -> } }

    @Test
    fun `a photo tagged to rotate becomes an upright portrait page`() {
        assertEquals(1, convert(listOf(rotatedJpeg("photo.jpg"))))
        PDDocument.load(output).use { doc ->
            val box = doc.getPage(0).mediaBox
            assertEquals(30f, box.width)
            assertEquals(60f, box.height)
        }
    }

    @Test
    fun `an image that cannot be decoded is skipped and not counted`() {
        assertEquals(2, convert(listOf(rotatedJpeg("a.jpg"), garbage("broken.jpg"), rotatedJpeg("b.jpg"))))
        PDDocument.load(output).use { assertEquals(2, it.numberOfPages) }
    }

    @Test
    fun `no decodable image is an error and writes nothing`() {
        assertThrows(IOException::class.java) { convert(listOf(garbage("x.jpg"), garbage("y.jpg"))) }
        assertFalse(output.exists())
    }
}
