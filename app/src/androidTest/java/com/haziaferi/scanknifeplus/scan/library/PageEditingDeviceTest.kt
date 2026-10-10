package com.haziaferi.scanknifeplus.scan.library

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haziaferi.scanknifeplus.scan.capture.ImageCodec
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scanner.cv.PageSize
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import java.io.File
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Filters and re-crops with the real codecs and scanner-core filters, on a device. */
@RunWith(AndroidJUnit4::class)
class PageEditingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "editing-test-${System.nanoTime()}")
    private val library = ScanLibrary(root)

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A 1200x1600 photo: a saturated orange top half and a blue bottom half. */
    private fun photo(): File = File(root, "photo.jpg").also { f ->
        root.mkdirs()
        val b = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawRect(0f, 0f, 1200f, 800f, Paint().apply { color = Color.rgb(240, 120, 20) })
            drawRect(0f, 800f, 1200f, 1600f, Paint().apply { color = Color.rgb(30, 60, 200) })
        }
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        b.recycle()
    }

    private fun pixel(file: File, fx: Double, fy: Double): IntArray {
        val image = ImageCodec.decodeScaled(ImageSource.of(file), 0)!!
        val i = ((fy * image.height).toInt() * image.width + (fx * image.width).toInt()) * 4
        return IntArray(3) { image.pixels[i + it].toInt() and 0xFF }
    }

    @Test
    fun grayscaleThenOriginalRestoresTheExactPage() {
        val doc = library.create()
        val page = library.addCapture(doc.id, ImageSource.of(photo()), null, keepOriginal = true)!!.pages.single()
        val pageBytes = library.file(doc.id, page.image).readBytes()

        val gray = library.applyFilter(doc.id, page.id, "Grayscale")!!.pages.single()
        val (r, g, b) = pixel(library.file(doc.id, gray.image), 0.5, 0.25).toList()
        assertTrue("grayscale pixel $r,$g,$b", abs(r - g) <= 3 && abs(g - b) <= 3)

        val restored = library.applyFilter(doc.id, page.id, "Original")!!.pages.single()
        assertArrayEquals(pageBytes, library.file(doc.id, restored.image).readBytes())
    }

    @Test
    fun aRecropTakesTheQuadFromTheOriginalAndTurnsIt() {
        val doc = library.create()
        val page = library.addCapture(doc.id, ImageSource.of(photo()), null, keepOriginal = true)!!.pages.single()
        // The left half of the photo's top half: 600x800 of orange.
        val topLeft = Quad(Pt(0.0, 0.0), Pt(0.5, 0.0), Pt(0.5, 0.5), Pt(0.0, 0.5))
        val upright = library.recropPage(doc.id, page.id, topLeft)!!.pages.single()
        assertEquals(PageSize(600, 800), ImageCodec.orientedSize(ImageSource.of(library.file(doc.id, upright.image))))
        val (r, _, b) = pixel(library.file(doc.id, upright.image), 0.5, 0.5).toList()
        assertTrue("orange expected, got r=$r b=$b", r > 200 && b < 80)

        val turned = library.recropPage(doc.id, page.id, topLeft, quarterTurns = 1)!!.pages.single()
        assertEquals(PageSize(800, 600), ImageCodec.orientedSize(ImageSource.of(library.file(doc.id, turned.image))))
        assertEquals(page.original, turned.original)
    }
}
