package com.haziaferi.scanknifeplus.scan.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** [CaptureStore.storeBook] with Robolectric's native graphics (the real Skia codecs). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookCaptureStoreTest {
    private val dir: File = Files.createTempDirectory("book-store").toFile()
    private val pages = File(dir, "left.jpg") to File(dir, "right.jpg")
    private val originals = File(dir, "orig_left.jpg") to File(dir, "orig_right.jpg")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun photo(width: Int, height: Int, color: (Int, Int) -> Int): ImageSource {
        val file = File(dir, "photo.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) for (y in 0 until height) bitmap.setPixel(x, y, color(x, y))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return ImageSource.of(file)
    }

    private fun assertColor(expected: Int, file: File, x: Int, y: Int) {
        val bitmap = BitmapFactory.decodeFile(file.path)
        val actual = bitmap.getPixel(x, y)
        bitmap.recycle()
        val close = abs(Color.red(expected) - Color.red(actual)) < 40 && abs(Color.green(expected) - Color.green(actual)) < 40 &&
            abs(Color.blue(expected) - Color.blue(actual)) < 40
        assertTrue("${file.name} at ($x, $y): expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}", close)
    }

    @Test
    fun `an upright spread is stored as its left and right pages, each with its own copy of the original`() {
        val source = photo(300, 200) { x, _ -> if (x < 150) Color.RED else Color.BLUE }
        val stored = CaptureStore.storeBook(source, null, 0.5, 0, pages, originals)!!
        assertEquals(listOf(pages.first, pages.second), stored.map { it.page })
        assertEquals(listOf(originals.first, originals.second), stored.map { it.original })
        assertEquals(listOf(150 to 200, 150 to 200), stored.map { it.pageWidth to it.pageHeight })
        assertTrue(stored.all { it.cropped })
        assertColor(Color.RED, pages.first, 75, 100)
        assertColor(Color.BLUE, pages.second, 75, 100)
        assertArrayEquals(originals.first.readBytes(), originals.second.readBytes())
        assertFalse(File(dir, "orig_right.jpg.tmp").exists())
    }

    @Test
    fun `a sideways book is stored upright, left page first`() {
        // A portrait photo with the book's top on its left: the bottom half is the left page (red, with a green mark at the reader's top-left,
        // the photo's bottom-left corner) and the top half the right page (blue).
        val source = photo(200, 300) { x, y -> if (x < 20 && y >= 280) Color.GREEN else if (y >= 150) Color.RED else Color.BLUE }
        val stored = CaptureStore.storeBook(source, null, 0.5, 1, pages, null)!!
        assertEquals(listOf(150 to 200, 150 to 200), stored.map { it.pageWidth to it.pageHeight })
        assertEquals(listOf(null, null), stored.map { it.original })
        assertColor(Color.GREEN, pages.first, 5, 5)
        assertColor(Color.RED, pages.first, 100, 150)
        assertColor(Color.BLUE, pages.second, 75, 100)
        assertFalse(originals.first.exists())
    }

    @Test
    fun `the spread quad is split, not the whole photo`() {
        // Portrait photo, fractional portrait quad over its middle: the split falls at x = 100 of the photo.
        val source = photo(200, 300) { x, _ -> if (x < 100) Color.RED else Color.BLUE }
        val spread = Quad(Pt(0.2, 0.1), Pt(0.8, 0.1), Pt(0.8, 0.9), Pt(0.2, 0.9))
        val stored = CaptureStore.storeBook(source, spread, 0.5, 0, pages, null)!!
        assertEquals(listOf(60 to 240, 60 to 240), stored.map { it.pageWidth to it.pageHeight })
        assertColor(Color.RED, pages.first, 30, 120)
        assertColor(Color.BLUE, pages.second, 30, 120)
    }

    @Test
    fun `a spread that cannot be split or read leaves no page behind`() {
        val source = photo(300, 200) { _, _ -> Color.WHITE }
        pages.first.writeText("reserved")
        pages.second.writeText("reserved")
        assertNull(CaptureStore.storeBook(source, null, 1.5, 0, pages, originals))
        assertFalse(pages.first.exists() || pages.second.exists() || originals.first.exists() || originals.second.exists())

        pages.first.writeText("reserved")
        assertNull(CaptureStore.storeBook(ImageSource { "not an image".byteInputStream() }, null, 0.5, 0, pages, originals))
        assertFalse(pages.first.exists() || originals.first.exists())
    }
}
