package com.haziaferi.scanknifeplus.scanner.cv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeMasksTest {
    private fun plane(w: Int, h: Int, f: (Int, Int) -> Int) = ByteArray(w * h) { f(it % w, it / w).toByte() }

    private fun columns(mask: ByteArray, w: Int, y: Int) = (0 until w).filter { mask[y * w + it].toInt() == 1 }

    @Test
    fun `areaResample averages the covered pixels, fractional ones by coverage`() {
        val src = plane(4, 2) { x, _ -> x * 40 }
        assertArrayEquals(byteArrayOf(20, 100, 20, 100), EdgeMasks.areaResample(src, 4, 2, 1, intArrayOf(0), 2, 2).single())
        // 3 -> 2: the middle pixel is split half and half.
        val three = plane(3, 1) { x, _ -> intArrayOf(0, 90, 180)[x] }
        assertArrayEquals(byteArrayOf(30, 150.toByte()), EdgeMasks.areaResample(three, 3, 1, 1, intArrayOf(0), 2, 1).single())
        // Channels of an RGBA buffer, in the order asked for.
        val rgba = byteArrayOf(10, 20, 30, 0, 50, 60, 70, 0)
        val (b, g) = EdgeMasks.areaResample(rgba, 2, 1, 4, intArrayOf(2, 1), 1, 1)
        assertArrayEquals(byteArrayOf(50), b)
        assertArrayEquals(byteArrayOf(40), g)
    }

    @Test
    fun `median blur removes salt noise and keeps an edge`() {
        val src = plane(7, 7) { x, y -> if (x == 3 && y == 3) 255 else if (x >= 5) 200 else 0 }
        val out = EdgeMasks.medianBlur3(src, 7, 7)
        assertEquals(0, out[3 * 7 + 3].toInt())
        assertEquals(listOf(5, 6), (0 until 7).filter { out[3 * 7 + it].toInt() != 0 })
    }

    @Test
    fun `median blur and the rectangle filters equal their brute-force definitions`() {
        val r = java.util.Random(3)
        val w = 23
        val h = 17
        val src = ByteArray(w * h) { r.nextInt(256).toByte() }
        val mask = ByteArray(w * h) { if (r.nextInt(4) == 0) 1 else 0 }
        val median = EdgeMasks.medianBlur3(src, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val nine = (-1..1).flatMap { dy -> (-1..1).map { dx -> src[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)].toInt() and 255 } }
                assertEquals("median at $x,$y", nine.sorted()[4], median[y * w + x].toInt() and 255)
            }
        }
        for (size in 2..4) {
            for (dilate in listOf(true, false)) {
                val out = EdgeMasks.rectFilter(mask, w, h, size, dilate)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val window = (-(size / 2)..size - 1 - size / 2).flatMap { dy ->
                            (-(size / 2)..size - 1 - size / 2).mapNotNull { dx ->
                                if (x + dx in 0 until w && y + dy in 0 until h) mask[(y + dy) * w + x + dx].toInt() else null
                            }
                        }
                        assertEquals("size $size dilate $dilate at $x,$y", if (dilate) window.max() else window.min(), out[y * w + x].toInt())
                    }
                }
            }
        }
    }

    @Test
    fun `closing fills a one pixel gap and dilation grows by one pixel`() {
        val gap = plane(9, 1) { x, _ -> if (x == 4) 0 else 1 }
        assertEquals((0 until 9).toList(), columns(EdgeMasks.close(gap, 9, 1, 4), 9, 0))
        val dot = plane(5, 5) { x, y -> if (x == 2 && y == 2) 1 else 0 }
        val grown = EdgeMasks.rectFilter(dot, 5, 5, 3, dilate = true)
        assertEquals(9, grown.count { it.toInt() == 1 })
    }

    @Test
    fun `canny thins a step edge to one line and needs a strong pixel to start one`() {
        val step = plane(20, 10) { x, _ -> if (x < 10) 40 else 200 }
        val edges = EdgeMasks.Canny(step, 20, 10).edges(20, 40)
        for (y in 0 until 10) assertEquals("row $y", 1, columns(edges, 20, y).size)
        // A weak edge (magnitude between the thresholds everywhere) with no strong pixel is dropped by hysteresis.
        val faint = plane(20, 10) { x, _ -> if (x < 10) 100 else 110 }
        assertEquals(0, EdgeMasks.Canny(faint, 20, 10).edges(20, 100).count { it.toInt() == 1 })
        assertEquals(10, EdgeMasks.Canny(faint, 20, 10).edges(20, 39).count { it.toInt() == 1 })
    }
}
