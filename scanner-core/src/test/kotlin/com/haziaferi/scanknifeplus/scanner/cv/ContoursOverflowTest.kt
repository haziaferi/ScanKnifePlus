package com.haziaferi.scanknifeplus.scanner.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OpenScan multiplies frame sizes in Dart's 64-bit ints; at 50000 px a 32-bit `width * height` wraps negative and flips the area checks. */
class ContoursOverflowTest {
    private fun square(from: Double, to: Double) = Quad(Pt(from, from), Pt(to, from), Pt(to, to), Pt(from, to))

    @Test
    fun `a full-frame quad on a 50000 px frame is plausible`() {
        assertTrue(Contours.isPlausibleQuad(square(0.0, 50000.0), 50000, 50000))
    }

    @Test
    fun `pickBestQuad on a 50000 px frame prefers the larger quad`() {
        val small = square(1000.0, 11000.0)
        val large = square(0.0, 50000.0)
        assertEquals(large, Contours.pickBestQuad(listOf(small, large), 50000, 50000))
    }
}
