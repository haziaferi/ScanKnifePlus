package com.haziaferi.scanknifeplus.scanner.cv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A non-finite corner makes the angle cosine NaN. Dart's clamp sends NaN to the upper bound (cos 1, angle 0), so OpenScan rejects the quad;
 * a plain coerceIn would let NaN through and accept it. Expected values come from running OpenScan's contours.dart on the same quads.
 */
class ContoursNonFiniteTest {
    private fun quad(tr: Pt = Pt(90.0, 10.0), bl: Pt = Pt(10.0, 70.0)) = Quad(Pt(10.0, 10.0), tr, Pt(90.0, 70.0), bl)

    @Test
    fun `quads with a non-finite corner are rejected like OpenScan`() {
        assertEquals(false, Contours.isPlausibleQuad(quad(tr = Pt(Double.POSITIVE_INFINITY, 10.0)), 100, 80))
        assertEquals(false, Contours.isPlausibleQuad(quad(bl = Pt(Double.NaN, 70.0)), 100, 80))
        assertEquals(true, Contours.isPlausibleQuad(quad(), 100, 80))
    }
}
