package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.ParityFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compares one-shot detection and the shared grayscale pipeline with OpenScan's Dart output (fixture: parity/detector.tsv). */
class DocumentDetectorParityTest {
    private val cases = ParityFixture.load("detector.tsv")

    @Test
    fun `fixture covers downscaled, unscaled and empty inputs`() {
        assertTrue(cases.size >= 6)
        assertTrue(cases.any { it.int("width") > DocumentDetector.DETECTION_MAX_DIMENSION || it.int("height") > DocumentDetector.DETECTION_MAX_DIMENSION })
        assertTrue(cases.any { it.raw("result") == "not_found" })
    }

    @Test
    fun `detectFromRgba matches OpenScan`() {
        for (c in cases) {
            val w = c.int("width")
            val h = c.int("height")
            val actual = DocumentDetector.detectFromRgba(c.bytes("rgba"), w, h)
            when (c.raw("result")) {
                "success" -> {
                    actual as DetectionResult.Success
                    assertEquals("$c: quad", c.raw("quad"), actual.quad.format())
                    assertEquals("$c: size", c.raw("result_size"), "${actual.imageWidth},${actual.imageHeight}")
                }
                "not_found" -> {
                    actual as DetectionResult.NotFound
                    assertEquals("$c: size", c.raw("result_size"), "${actual.imageWidth},${actual.imageHeight}")
                }
                else -> error("$c: unexpected expected result ${c.raw("result")}")
            }
        }
    }

    @Test
    fun `detectQuadFromGrayscale matches OpenScan with and without a previous quad`() {
        for (c in cases) {
            val w = c.int("width")
            val h = c.int("height")
            val gray = EdgeDetection.rgbaToGrayscale(c.bytes("rgba"), w, h)
            assertEquals("$c: gray_quad", c.raw("gray_quad"), DocumentDetector.detectQuadFromGrayscale(gray, w, h).format())
            assertEquals("$c: gray_quad_with_previous", c.raw("gray_quad_with_previous"), DocumentDetector.detectQuadFromGrayscale(gray, w, h, c.quad("previous")).format())
        }
    }

    @Test
    fun `detectFromRgba reports bad input as Failure instead of throwing`() {
        val result = DocumentDetector.detectFromRgba(ByteArray(10), 100, 100)
        assertTrue(result is DetectionResult.Failure)
    }

    @Test
    fun `downscaleRgba returns the same buffer when the size is unchanged`() {
        val src = ByteArray(16)
        assertSame(src, DocumentDetector.downscaleRgba(src, 2, 2, 2, 2))
    }
}
