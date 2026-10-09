package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.ParityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compares every edge-detection step with OpenScan's Dart output for the same input (fixture: parity/primitives.tsv). */
class EdgeDetectionParityTest {
    private val cases = ParityFixture.load("primitives.tsv")

    @Test
    fun `fixture is present`() {
        assertTrue(cases.size >= 5)
    }

    @Test
    fun `pipeline matches OpenScan step by step`() {
        for (c in cases) {
            val w = c.int("width")
            val h = c.int("height")
            val gray = EdgeDetection.rgbaToGrayscale(c.bytes("rgba"), w, h)
            c.assertBytes("gray", gray)
            val blur = EdgeDetection.gaussianBlur3(gray, w, h)
            c.assertBytes("blur", blur)
            val sobel = EdgeDetection.sobelMagnitude(blur, w, h)
            c.assertBytes("sobel", sobel)
            val otsu = EdgeDetection.otsuThreshold(sobel)
            assertEquals("$c: otsu", c.int("otsu"), otsu)
            val mask = EdgeDetection.threshold(sobel, otsu)
            c.assertBytes("mask", mask)
            c.assertBytes("dilate1", EdgeDetection.dilate(mask, w, h, 1))
            c.assertBytes("dilate4", EdgeDetection.dilate(mask, w, h, 4))
            assertEquals("$c: otsu_gray", c.int("otsu_gray"), EdgeDetection.otsuThreshold(gray))
        }
    }
}
