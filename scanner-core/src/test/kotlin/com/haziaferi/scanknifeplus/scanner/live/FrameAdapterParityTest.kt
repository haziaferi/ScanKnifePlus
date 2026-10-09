package com.haziaferi.scanknifeplus.scanner.live

import com.haziaferi.scanknifeplus.scanner.ParityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compares the frame adapter with OpenScan's Dart output (fixture: parity/frame.tsv). */
class FrameAdapterParityTest {
    private val cases = ParityFixture.load("frame.tsv")
    private val frames = cases.filter { it.name.startsWith("y_") }

    @Test
    fun `fixture covers the expected cases`() {
        assertEquals(FrameAdapter.LIVE_DETECTION_MAX_DIMENSION, cases.single { it.name == "constants" }.int("live_detection_max_dimension"))
        assertEquals(9, frames.size)
        assertTrue(frames.any { it.int("bytes_per_row") > it.int("width") })
    }

    @Test
    fun `Y plane downsampling matches OpenScan`() {
        for (c in frames) {
            val gray = FrameAdapter.grayscaleFromYPlane(c.bytes("plane"), c.int("bytes_per_row"), c.int("width"), c.int("height"))!!
            assertEquals("$c: length", c.int("out_len"), gray.size)
            c.assertBytes("gray", gray)
        }
    }

    @Test
    fun `downsampled size matches what OpenScan submits for detection`() {
        for (c in frames) {
            val (w, h) = FrameAdapter.downsampledSize(c.int("width"), c.int("height"))!!
            assertEquals("$c", c.raw("out_size"), "$w,$h")
        }
    }

    @Test
    fun `other target edges give OpenScan's output lengths`() {
        for (entry in cases.single { it.name == "target_sizes" }.raw("lengths").split('|')) {
            val (dims, expected) = entry.split('=')
            val (w, h, target) = dims.split(',').map { it.toInt() }
            val out = FrameAdapter.grayscaleFromYPlane(ByteArray((w + 1) * h), w + 1, w, h, target)!!
            assertEquals(entry, expected.toInt(), out.size)
        }
    }

    @Test
    fun `empty frames are rejected like OpenScan`() {
        val results = cases.single { it.name == "rejected" }.raw("results").split('|').associate { it.split('=').let { (k, v) -> k to v } }
        for ((w, h) in listOf(0 to 4, 4 to 0, -1 to 4)) {
            assertEquals("null", results["${w}x$h"])
            assertNull(FrameAdapter.grayscaleFromYPlane(ByteArray(16), 4, w, h))
        }
        // OpenScan also refuses jpeg, nv21 and unknown frames; the Kotlin API only accepts a Y plane, so those formats cannot reach it.
        assertEquals(listOf("null", "null", "null"), listOf("jpeg", "nv21", "unknown").map { results[it] })
    }
}

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
