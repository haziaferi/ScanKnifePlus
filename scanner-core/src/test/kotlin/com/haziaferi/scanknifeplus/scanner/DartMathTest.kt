package com.haziaferi.scanknifeplus.scanner

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
