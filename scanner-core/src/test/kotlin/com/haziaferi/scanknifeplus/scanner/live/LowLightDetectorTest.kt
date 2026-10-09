package com.haziaferi.scanknifeplus.scanner.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-written: OpenScan's check lives in a Flutter State (live_scan_screen.dart _updateLowLight), so there is no Dart fixture for it. */
class LowLightDetectorTest {
    private fun frame(value: Int, size: Int = 320 * 180) = ByteArray(size) { value.toByte() }

    @Test
    fun `turns on below 55 and off only at 63 or above`() {
        val d = LowLightDetector()
        assertFalse(d.update(frame(55)))
        assertFalse(d.isLowLight)
        assertTrue(d.update(frame(54)))
        assertTrue(d.isLowLight)
        assertFalse(d.update(frame(62)))
        assertTrue(d.isLowLight)
        assertTrue(d.update(frame(63)))
        assertFalse(d.isLowLight)
    }

    @Test
    fun `averages every 16th sample and reads bytes as unsigned`() {
        // Sampled positions (0, 16, 32...) are bright, the rest dark: the mean of the samples alone decides.
        val bright = ByteArray(320) { if (it % 16 == 0) 200.toByte() else 0 }
        val d = LowLightDetector()
        assertFalse(d.update(bright))
        val dark = ByteArray(320) { if (it % 16 == 0) 0 else 200.toByte() }
        assertTrue(d.update(dark))
        assertTrue(d.isLowLight)
    }

    @Test
    fun `an empty frame changes nothing`() {
        val d = LowLightDetector()
        assertFalse(d.update(ByteArray(0)))
        assertFalse(d.isLowLight)
    }
}
