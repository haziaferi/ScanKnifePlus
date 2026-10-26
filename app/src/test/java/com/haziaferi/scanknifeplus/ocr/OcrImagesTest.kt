package com.haziaferi.scanknifeplus.ocr

import com.haziaferi.scanknifeplus.scanner.cv.PageSize
import com.haziaferi.scanknifeplus.scanner.cv.RgbaImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrImagesTest {
    @Test
    fun `target size puts the long edge at the DPI of the assumed page and the short edge by the image's aspect`() {
        // A4 at 200 DPI: 297 mm is 2339 px; an A4-shaped stored page (1697 x 2400) keeps its own aspect.
        assertEquals(PageSize(1654, 2339), OcrImages.targetSize(1697, 2400, 297.0, 200))
        // A receipt-shaped page: only the long edge comes from the millimetres.
        assertEquals(PageSize(500, 1496), OcrImages.targetSize(802, 2400, 190.0, 200))
    }

    @Test
    fun `target size follows the image's orientation`() {
        assertEquals(PageSize(2339, 1654), OcrImages.targetSize(2400, 1697, 297.0, 200))
        assertEquals(PageSize(2339, 2339), OcrImages.targetSize(1000, 1000, 297.0, 200))
    }

    @Test
    fun `resizing averages when shrinking and interpolates when enlarging`() {
        val src = GreyImage(4, 2, byteArrayOf(0, 100, 50, 50, 100, 0, 50, 50))
        assertArrayEquals(byteArrayOf(50, 50), OcrImages.resized(src, 2, 1).pixels)
        val enlarged = OcrImages.resized(GreyImage(2, 1, byteArrayOf(0, 100)), 4, 1)
        assertArrayEquals(byteArrayOf(0, 25, 75, 100), enlarged.pixels)
    }

    @Test
    fun `the grey copy is the scanner's contrast-stretched Grayscale filter`() {
        val rgba = ByteArray(4 * 4)
        listOf(60, 80, 100, 120).forEachIndexed { i, v -> for (c in 0 until 3) rgba[i * 4 + c] = v.toByte(); rgba[i * 4 + 3] = -1 }
        val grey = OcrImages.grey(RgbaImage(4, 1, rgba))
        assertEquals(0, grey.pixels[0].toInt() and 0xff)
        assertEquals(255, grey.pixels[3].toInt() and 0xff)
    }

    /** A 3 x 2 image whose pixels are numbered 0 to 5 row by row. */
    private val numbered = GreyImage(3, 2, byteArrayOf(0, 1, 2, 3, 4, 5))

    @Test
    fun `quarter turns move pixels clockwise`() {
        assertArrayEquals(byteArrayOf(3, 0, 4, 1, 5, 2), OcrImages.rotatedClockwise(numbered, 90).pixels)
        assertArrayEquals(byteArrayOf(5, 4, 3, 2, 1, 0), OcrImages.rotatedClockwise(numbered, 180).pixels)
        assertArrayEquals(byteArrayOf(2, 5, 1, 4, 0, 3), OcrImages.rotatedClockwise(numbered, 270).pixels)
        assertEquals(2, OcrImages.rotatedClockwise(numbered, 90).width)
    }

    private fun raw(text: String, l: Int, t: Int, r: Int, b: Int, block: Boolean = false, para: Boolean = false, line: Boolean = false) =
        RawWord(text, 90f, l, t, r, b, block, para, line)

    @Test
    fun `a box maps back through every turn onto the page as it was passed in`() {
        // The page is 400 x 200 (landscape) with a word at x 40-120, y 20-60: fractions 0.1-0.3 across and 0.1-0.3 down.
        val expected = listOf(0.1f, 0.1f, 0.3f, 0.3f)
        val page = GreyImage(400, 200, ByteArray(400 * 200))
        // Where the word lands in the page turned clockwise by each angle, found by turning a marked image.
        for (turn in listOf(0, 90, 180, 270)) {
            val marked = page.pixels.copyOf()
            for (y in 20 until 60) for (x in 40 until 120) marked[y * 400 + x] = 1
            val turned = OcrImages.rotatedClockwise(GreyImage(400, 200, marked), turn)
            var l = turned.width; var t = turned.height; var r = 0; var b = 0
            for (y in 0 until turned.height) for (x in 0 until turned.width) if (turned.pixels[y * turned.width + x].toInt() == 1) {
                l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x + 1); b = maxOf(b, y + 1)
            }
            val word = OcrImages.words(listOf(raw("w", l, t, r, b, block = true)), turned.width, turned.height, turn).single()
            assertEquals("turn $turn", expected, listOf(word.left, word.top, word.right, word.bottom).map { Math.round(it * 1000) / 1000f })
        }
    }

    @Test
    fun `blocks, paragraphs and lines count up in reading order and empty words are skipped`() {
        val words = OcrImages.words(
            listOf(
                raw("One", 0, 0, 1, 1, block = true, para = true, line = true),
                raw("two", 0, 0, 1, 1),
                raw(" ", 0, 0, 1, 1, line = true),
                raw("three", 0, 0, 1, 1),
                raw("", 0, 0, 1, 1, block = true, para = true, line = true),
                raw(" four ", 0, 0, 1, 1),
            ),
            10, 10, 0,
        )
        assertEquals(listOf("One", "two", "three", "four"), words.map { it.text })
        assertEquals(listOf(0, 0, 0, 1), words.map { it.block })
        assertEquals(listOf(0, 0, 0, 1), words.map { it.paragraph })
        assertEquals(listOf(0, 0, 1, 2), words.map { it.line })
        assertEquals("One two\nthree\n\nfour", OcrPage(words).text())
    }
}
