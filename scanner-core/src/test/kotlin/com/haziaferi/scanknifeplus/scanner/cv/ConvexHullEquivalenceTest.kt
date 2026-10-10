package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.dartSort
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** The column-extremes hull must equal OpenScan's chain over every sorted pixel, vertex for vertex and in the same order. */
class ConvexHullEquivalenceTest {
    /** OpenScan's convexHull as ported before the column-extremes shortcut. */
    private fun referenceHull(points: List<Pt>): List<Pt> {
        val pts = points.toMutableList()
        pts.dartSort { a, b -> if (a.x != b.x) a.x.compareTo(b.x) else a.y.compareTo(b.y) }
        if (pts.size < 3) return pts

        fun cross(o: Pt, a: Pt, b: Pt): Double = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

        val lower = ArrayList<Pt>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = ArrayList<Pt>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper += p
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun pixels(mask: ByteArray, width: Int): List<Pt> =
        mask.indices.filter { mask[it].toInt() == 1 }.map { Pt((it % width).toDouble(), (it / width).toDouble()) }

    private fun assertSameHull(label: String, points: List<Pt>) {
        assertEquals(label, referenceHull(points), Contours.convexHull(points))
    }

    @Test
    fun `random masks give the same hull`() {
        val r = Random(7)
        repeat(2000) { n ->
            val w = 1 + r.nextInt(40)
            val h = 1 + r.nextInt(40)
            val density = r.nextDouble()
            val mask = ByteArray(w * h) { if (r.nextDouble() < density) 1 else 0 }
            val pts = pixels(mask, w)
            if (pts.isNotEmpty()) assertSameHull("random $n (${w}x$h)", pts)
        }
    }

    @Test
    fun `thick dilated masks give the same hull`() {
        val r = Random(11)
        repeat(300) { n ->
            val w = 20 + r.nextInt(150)
            val h = 20 + r.nextInt(150)
            val mask = ByteArray(w * h)
            repeat(1 + r.nextInt(12)) { mask[r.nextInt(w * h)] = 1 }
            val dilated = EdgeDetection.dilate(mask, w, h, 1 + r.nextInt(6))
            assertSameHull("dilated $n (${w}x$h)", pixels(dilated, w).shuffled(r))
        }
    }

    @Test
    fun `single rows, single columns and tiny sets give the same hull`() {
        assertSameHull("row", (3..12).map { Pt(it.toDouble(), 5.0) })
        assertSameHull("column", (3..12).map { Pt(5.0, it.toDouble()) })
        assertSameHull("diagonal", (0..9).map { Pt(it.toDouble(), it.toDouble()) })
        assertSameHull("square", listOf(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(0.0, 1.0), Pt(1.0, 1.0)))
        assertSameHull("pair", listOf(Pt(4.0, 2.0), Pt(1.0, 7.0)))
        assertSameHull("single", listOf(Pt(4.0, 2.0)))
    }
}
