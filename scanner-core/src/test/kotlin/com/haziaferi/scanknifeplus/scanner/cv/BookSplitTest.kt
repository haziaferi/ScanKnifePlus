package com.haziaferi.scanknifeplus.scanner.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Book mode's split of a spread into its left and right pages. */
class BookSplitTest {
    private val spread = Quad(Pt(0.0, 0.0), Pt(200.0, 0.0), Pt(200.0, 100.0), Pt(0.0, 100.0))

    /** A skewed, rotated spread, as a hand-held shot of a book gives. */
    private val skewed = Quad(Pt(30.0, 12.0), Pt(410.0, 60.0), Pt(380.0, 300.0), Pt(10.0, 250.0))

    private fun assertQuad(expected: Quad, actual: Quad) {
        for ((e, a) in expected.points.zip(actual.points)) {
            assertEquals("x of $actual", e.x, a.x, 1e-9)
            assertEquals("y of $actual", e.y, a.y, 1e-9)
        }
    }

    private fun lerp(a: Pt, b: Pt, t: Double) = Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /** The corners of a quad seen from the image, given its corners as the reader sees them after [turns] clockwise quarter turns. */
    private fun imageCorners(book: Quad, turns: Int): Quad {
        val c = book.points
        return Quad(c[turns % 4], c[(turns + 1) % 4], c[(turns + 2) % 4], c[(turns + 3) % 4])
    }

    @Test
    fun `an upright spread splits at the middle of its top and bottom edges by default`() {
        assertQuad(Quad(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0)), BookSplit.page(spread, BookPage.LEFT))
        assertQuad(Quad(Pt(100.0, 0.0), Pt(200.0, 0.0), Pt(200.0, 100.0), Pt(100.0, 100.0)), BookSplit.page(spread, BookPage.RIGHT))
    }

    @Test
    fun `the split line runs between the given fractions of a skewed spread's top and bottom edges`() {
        val top = lerp(skewed.topLeft, skewed.topRight, 0.3)
        val bottom = lerp(skewed.bottomLeft, skewed.bottomRight, 0.3)
        assertQuad(Quad(skewed.topLeft, top, bottom, skewed.bottomLeft), BookSplit.page(skewed, BookPage.LEFT, 0.3))
        assertQuad(Quad(top, skewed.topRight, skewed.bottomRight, bottom), BookSplit.page(skewed, BookPage.RIGHT, 0.3))
        // At the ends of the range one page is the whole spread.
        assertQuad(skewed, BookSplit.page(skewed, BookPage.RIGHT, 0.0))
        assertQuad(skewed, BookSplit.page(skewed, BookPage.LEFT, 1.0))
    }

    @Test
    fun `a sideways book is split along the reader's top and bottom edges, in the image's corner order`() {
        // A portrait photo of a book turned so its top is on the image's left: one clockwise turn makes it upright.
        val photo = Quad(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 200.0), Pt(0.0, 200.0))
        // The left page is the image's bottom half, its corners named as the image sees them.
        assertQuad(Quad(Pt(0.0, 100.0), Pt(100.0, 100.0), Pt(100.0, 200.0), Pt(0.0, 200.0)), BookSplit.page(photo, BookPage.LEFT, quarterTurns = 1))
        assertQuad(Quad(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0)), BookSplit.page(photo, BookPage.RIGHT, quarterTurns = 1))
    }

    @Test
    fun `a skewed spread at every turn splits as the same spread upright`() {
        for (turns in -4..7) {
            val photo = imageCorners(skewed, turns.mod(4))
            for (page in BookPage.entries) {
                val upright = BookSplit.page(skewed, page, 0.42)
                assertQuad(imageCorners(upright, turns.mod(4)), BookSplit.page(photo, page, 0.42, turns))
            }
        }
    }

    @Test
    fun `a split outside the spread is refused`() {
        for (split in listOf(-0.01, 1.01, Double.NaN)) {
            assertThrows(IllegalArgumentException::class.java) { BookSplit.page(spread, BookPage.LEFT, split) }
            assertNull(BookSplit.cropPage(image(4, 4) { _, _ -> WHITE }, spread, BookPage.LEFT, split, 0, null))
        }
    }

    @Test
    fun `cropPage cuts a sideways book's pages out upright`() {
        // 60 x 200, book top on the left: the bottom half is the left page (red), the top half the right page (blue). A green mark sits at the
        // left page's top-left corner as the reader sees it, which is the image's bottom-left corner.
        val photo = image(60, 200) { x, y -> if (x < 6 && y >= 190) GREEN else if (y >= 100) RED else BLUE }
        val whole = Quad(Pt(0.0, 0.0), Pt(60.0, 0.0), Pt(60.0, 200.0), Pt(0.0, 200.0))

        val left = BookSplit.cropPage(photo, whole, BookPage.LEFT, 0.5, 1, null)!!
        assertEquals(100, left.width) // turned upright: the reader's page is wider than tall here
        assertEquals(60, left.height)
        assertEquals(GREEN, left.rgb(1, 1))
        assertEquals(RED, left.rgb(50, 30))
        assertEquals(RED, left.rgb(98, 58))

        val right = BookSplit.cropPage(photo, whole, BookPage.RIGHT, 0.5, 1, null)!!
        assertEquals(100, right.width)
        assertEquals(60, right.height)
        assertEquals(BLUE, right.rgb(50, 30))
    }

    @Test
    fun `cropPage caps each page at maxEdge`() {
        val photo = image(400, 100) { x, _ -> if (x < 200) RED else BLUE }
        val whole = Quad(Pt(0.0, 0.0), Pt(400.0, 0.0), Pt(400.0, 100.0), Pt(0.0, 100.0))
        val page = BookSplit.cropPage(photo, whole, BookPage.RIGHT, 0.5, 0, 50)!!
        assertEquals(50, page.width)
        assertEquals(25, page.height)
        assertEquals(BLUE, page.rgb(25, 12))
    }

    private fun image(width: Int, height: Int, color: (Int, Int) -> Int): RgbaImage {
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val c = color(x, y)
            val i = (y * width + x) * 4
            pixels[i] = (c shr 16).toByte()
            pixels[i + 1] = (c shr 8).toByte()
            pixels[i + 2] = c.toByte()
            pixels[i + 3] = 255.toByte()
        }
        return RgbaImage(width, height, pixels)
    }

    private fun RgbaImage.rgb(x: Int, y: Int): Int {
        val i = (y * width + x) * 4
        return ((pixels[i].toInt() and 0xFF) shl 16) or ((pixels[i + 1].toInt() and 0xFF) shl 8) or (pixels[i + 2].toInt() and 0xFF)
    }

    private companion object {
        const val RED = 0xFF0000
        const val GREEN = 0x00FF00
        const val BLUE = 0x0000FF
        const val WHITE = 0xFFFFFF
    }
}
