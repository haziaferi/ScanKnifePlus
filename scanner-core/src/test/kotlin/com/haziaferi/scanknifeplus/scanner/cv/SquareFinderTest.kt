package com.haziaferi.scanknifeplus.scanner.cv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SquareFinderTest {
    private fun mask(w: Int, h: Int, f: (Int, Int) -> Boolean) = ByteArray(w * h) { if (f(it % w, it / w)) 1 else 0 }

    private fun corners(c: IntArray) = (0 until c.size / 2).map { c[2 * it] to c[2 * it + 1] }.toSet()

    @Test
    fun `a filled rectangle has one outer border through its four corners`() {
        val contours = ContourTracing.findContours(mask(10, 8) { x, y -> x in 2..6 && y in 1..5 }, 10, 8)
        assertEquals(1, contours.size)
        assertEquals(setOf(2 to 1, 6 to 1, 6 to 5, 2 to 5), corners(contours[0]))
        assertEquals(16.0, ContourTracing.area(contours[0]), 0.0)
        assertEquals(16.0, ContourTracing.arcLength(contours[0]), 0.0)
    }

    @Test
    fun `a ring has an outer and a hole border, and a lone pixel is a contour of one point`() {
        val ring = mask(12, 12) { x, y -> x in 1..10 && y in 1..10 && !(x in 4..7 && y in 4..7) }
        val contours = ContourTracing.findContours(ring, 12, 12)
        assertEquals(2, contours.size)
        assertEquals(setOf(1 to 1, 10 to 1, 10 to 10, 1 to 10), corners(contours[0]))
        // The hole border runs through the ring pixels next to the hole, its corners cut diagonally.
        assertEquals(setOf(4 to 3, 7 to 3, 8 to 4, 8 to 7, 7 to 8, 4 to 8, 3 to 7, 3 to 4), corners(contours[1]))
        assertArrayEquals(intArrayOf(2, 3), ContourTracing.findContours(mask(5, 5) { x, y -> x == 2 && y == 3 }, 5, 5).single())
    }

    @Test
    fun `approxPolyDP reduces a slightly wavy quadrilateral to its corners`() {
        // A 200 x 120 rectangle traced with a one pixel wobble on every side.
        val pts = ArrayList<Int>()
        for (x in 0 until 200 step 10) pts += listOf(x, if ((x / 10) % 2 == 0) 0 else 1)
        for (y in 0 until 120 step 10) pts += listOf(200 + if ((y / 10) % 2 == 0) 0 else 1, y)
        for (x in 200 downTo 1 step 10) pts += listOf(x, 120 + if ((x / 10) % 2 == 0) 0 else 1)
        for (y in 120 downTo 1 step 10) pts += listOf(if ((y / 10) % 2 == 0) 0 else 1, y)
        val contour = pts.toIntArray()
        val approx = ContourTracing.approxPolyDP(contour, ContourTracing.arcLength(contour) * 0.02)
        assertEquals(setOf(0 to 0, 200 to 0, 200 to 120, 0 to 120), corners(approx))
    }

    @Test
    fun `isConvex rejects a bow tie and a straight angle`() {
        assertTrue(ContourTracing.isConvex(intArrayOf(0, 0, 10, 0, 10, 10, 0, 10)))
        assertTrue(ContourTracing.isConvex(intArrayOf(0, 0, 0, 10, 10, 10, 10, 0)))
        assertFalse(ContourTracing.isConvex(intArrayOf(0, 0, 10, 10, 10, 0, 0, 10)))
        assertFalse(ContourTracing.isConvex(intArrayOf(0, 0, 5, 0, 10, 0, 10, 10)))
    }

    @Test
    fun `maxCosine is 0 for a rectangle and grows with skew`() {
        assertEquals(0.0, SquareFinder.maxCosine(intArrayOf(0, 0, 10, 0, 10, 5, 0, 5)), 1e-9)
        assertEquals(0.5, SquareFinder.maxCosine(intArrayOf(0, 0, 1000, 0, 1500, 866, 500, 866)), 0.001)
    }

    @Test
    fun `a bright page is found on the threshold mask, a page cut off by the frame through the border`() {
        val w = 120
        val h = 160
        fun planeOf(page: (Int, Int) -> Boolean): ByteArray {
            val b = SquareFinder.BORDER
            val bordered = EdgeMasks.addBorder(ByteArray(w * h) { if (page(it % w, it / w)) 230.toByte() else 70 }, w, h, b)
            return EdgeMasks.medianBlur3(bordered, w + 2 * b, h + 2 * b)
        }

        fun assertQuad(expected: Quad, actual: Quad) {
            for ((e, a) in expected.points.zip(actual.points)) {
                assertEquals("x of $actual", e.x, a.x, 3.0)
                assertEquals("y of $actual", e.y, a.y, 3.0)
            }
        }

        val inside = SquareFinder.findBest(listOf(planeOf { x, y -> x in 20..99 && y in 30..129 }), w + 20, h + 20)
        assertQuad(Quad(Pt(20.0, 30.0), Pt(99.0, 30.0), Pt(99.0, 129.0), Pt(20.0, 129.0)), inside!!.toQuad(SquareFinder.BORDER, 1.0, 1.0))

        // The page runs off the right edge: only the border makes a closed outline there.
        val cut = SquareFinder.findBest(listOf(planeOf { x, y -> x >= 20 && y in 30..129 }), w + 20, h + 20)
        assertQuad(Quad(Pt(20.0, 30.0), Pt(w - 1.0, 30.0), Pt(w - 1.0, 129.0), Pt(20.0, 129.0)), cut!!.toQuad(SquareFinder.BORDER, 1.0, 1.0))
    }
}
