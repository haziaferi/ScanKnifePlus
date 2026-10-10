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

    /** A [width]x[height] photo in quadrants: orange top-left, green top-right, blue bottom-left, white bottom-right. */
    private fun photo(width: Int = 1200, height: Int = 1600): File = File(root, "photo.jpg").also { f ->
        root.mkdirs()
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val w = width.toFloat()
        val h = height.toFloat()
        Canvas(b).apply {
            drawRect(0f, 0f, w / 2, h / 2, Paint().apply { color = Color.rgb(240, 120, 20) })
            drawRect(w / 2, 0f, w, h / 2, Paint().apply { color = Color.rgb(20, 200, 40) })
            drawRect(0f, h / 2, w / 2, h, Paint().apply { color = Color.rgb(30, 60, 200) })
            drawRect(w / 2, h / 2, w, h, Paint().apply { color = Color.WHITE })
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

    private fun assertColor(expected: Int, actual: IntArray, label: String) {
        val e = intArrayOf(Color.red(expected), Color.green(expected), Color.blue(expected))
        assertTrue("$label: expected ${e.toList()}, got ${actual.toList()}", e.indices.all { abs(e[it] - actual[it]) < 40 })
    }

    @Test
    fun aRecropTakesTheQuadFromTheOriginalAndTurnsItClockwise() {
        val doc = library.create()
        val page = library.addCapture(doc.id, ImageSource.of(photo()), null, keepOriginal = true)!!.pages.single()
        // The top half of the photo: 1200x800, orange on the left and green on the right.
        val topHalf = Quad(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 0.5), Pt(0.0, 0.5))
        val upright = library.file(doc.id, library.recropPage(doc.id, page.id, topHalf)!!.pages.single().image)
        assertEquals(PageSize(1200, 800), ImageCodec.orientedSize(ImageSource.of(upright)))
        assertColor(Color.rgb(240, 120, 20), pixel(upright, 0.25, 0.5), "left of the crop")
        assertColor(Color.rgb(20, 200, 40), pixel(upright, 0.75, 0.5), "right of the crop")

        // Turned clockwise, the crop's left (orange) becomes the top and its right (green) the bottom.
        val turnedPage = library.recropPage(doc.id, page.id, topHalf, quarterTurns = 1)!!.pages.single()
        val turned = library.file(doc.id, turnedPage.image)
        assertEquals(PageSize(800, 1200), ImageCodec.orientedSize(ImageSource.of(turned)))
        assertColor(Color.rgb(240, 120, 20), pixel(turned, 0.5, 0.25), "top after a clockwise turn")
        assertColor(Color.rgb(20, 200, 40), pixel(turned, 0.5, 0.75), "bottom after a clockwise turn")
        assertEquals(page.original, turnedPage.original)
    }

    @Test
    fun aRecropIsFittedToThePageCap() {
        val doc = library.create()
        // 3000x4000 is stored with a 2400x3200 original; the whole original re-cropped is fitted to 1800x2400.
        val page = library.addCapture(doc.id, ImageSource.of(photo(3000, 4000)), null, keepOriginal = true)!!.pages.single()
        val whole = Quad(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0))
        val recropped = library.recropPage(doc.id, page.id, whole)!!.pages.single()
        assertEquals(PageSize(1800, 2400), ImageCodec.orientedSize(ImageSource.of(library.file(doc.id, recropped.image))))
    }
}
