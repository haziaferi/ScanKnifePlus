package com.haziaferi.scanknifeplus.scanner

import com.haziaferi.scanknifeplus.scanner.filter.ImageFilterUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DartMathTest {
    @Test
    fun `dartRound sends ties away from zero like Dart`() {
        assertEquals(1, dartRound(0.5))
        assertEquals(-1, dartRound(-0.5))
        assertEquals(3, dartRound(2.5))
        assertEquals(-3, dartRound(-2.5))
        assertEquals(0, dartRound(0.49999999999999994))
        assertEquals(-2, dartRound(-1.6))
        assertEquals(0, dartRound(-0.4))
    }

    @Test
    fun `u8 reads bytes as unsigned`() {
        val b = byteArrayOf(0, 127, -128, -1)
        assertEquals(listOf(0, 127, 128, 255), b.indices.map { b.u8(it) })
        b.setU8(0, 200)
        assertEquals(200, b.u8(0))
    }
}

class DartMathNonFiniteTest {
    @Test(expected = UnsupportedOperationException::class)
    fun `dartRound throws on NaN like Dart`() {
        dartRound(Double.NaN)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `dartFloor throws on infinity like Dart`() {
        dartFloor(Double.POSITIVE_INFINITY)
    }

    @Test
    fun `dartToInt truncates towards zero`() {
        assertEquals(-2, dartToInt(-2.9))
        assertEquals(2, dartToInt(2.9))
    }

    // Dart ints are 64-bit and saturate, so results past the Int range must keep their sign rather than wrap.
    @Test
    fun `results beyond the Int range saturate instead of wrapping`() {
        assertEquals(Int.MAX_VALUE, dartRound(3.0e9))
        assertEquals(Int.MIN_VALUE, dartRound(-3.0e9))
        assertEquals(Int.MAX_VALUE, dartRound(1.0e30))
        assertEquals(Int.MAX_VALUE, dartFloor(3.0e9))
        assertEquals(Int.MIN_VALUE, dartToInt(-1.0e30))
    }

    // Expected bytes come from running OpenScan's image_filter_utils.dart on the same input.
    @Test
    fun `huge saturation clamps like Dart instead of wrapping`() {
        for (s in listOf(2e7, 3e7)) {
            val b = byteArrayOf(-1, 0, 0, -1)
            ImageFilterUtils.saturation(b, s)
            assertArrayEquals(byteArrayOf(-1, 0, 0, -1), b)
        }
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `contrast with an infinite factor throws like Dart`() {
        ImageFilterUtils.contrast(byteArrayOf(10, -128, -6, -1), 259.0 / 255)
    }
}

class DartClampTest {
    @Test
    fun `dartClamp sends NaN to the upper bound like Dart`() {
        assertEquals(1.0, Double.NaN.dartClamp(0.0, 1.0), 0.0)
        assertEquals(0.0, (-0.5).dartClamp(0.0, 1.0), 0.0)
        assertEquals(0.25, 0.25.dartClamp(0.0, 1.0), 0.0)
    }
}
