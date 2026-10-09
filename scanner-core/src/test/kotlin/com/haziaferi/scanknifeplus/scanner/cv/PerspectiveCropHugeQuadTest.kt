package com.haziaferi.scanknifeplus.scanner.cv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Edges past 2^31 pixels: Dart's 64-bit `round().clamp(1, 1 << 16)` gives 65536 (checked with Dart 3.13.5), where a 32-bit wrap would give 1.
 * Warping such a page cannot allocate, and OpenScan's catch-all then returns null.
 */
class PerspectiveCropHugeQuadTest {
    private val huge = Quad(Pt(0.0, 0.0), Pt(3.0e9, 0.0), Pt(3.0e9, 3.0e9), Pt(0.0, 3.0e9))

    @Test
    fun `outputSize clamps huge edges to 65536 like Dart`() {
        val size = PerspectiveCrop.outputSize(huge)
        assertEquals(65536, size.width)
        assertEquals(65536, size.height)
    }

    @Test
    fun `warp of a page too large to allocate returns null`() {
        assertNull(PerspectiveCrop.warp(RgbaImage(2, 2, ByteArray(16)), huge, null, null))
    }
}
