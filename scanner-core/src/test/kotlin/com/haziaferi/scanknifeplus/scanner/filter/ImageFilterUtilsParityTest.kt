package com.haziaferi.scanknifeplus.scanner.filter

import com.haziaferi.scanknifeplus.scanner.ParityFixture
import org.junit.Test

/** Compares the per-pixel filter primitives with OpenScan's Dart output (fixture: parity/primitives.tsv). */
class ImageFilterUtilsParityTest {
    private val cases = ParityFixture.load("primitives.tsv")

    @Test
    fun `saturation matches OpenScan`() {
        for (c in cases) {
            for (s in listOf(-1.5, -0.3, 0.0, 0.6, 1.0, 2.0)) {
                val b = c.bytes("rgba")
                ImageFilterUtils.saturation(b, s)
                c.assertBytes("saturation_$s", b)
            }
        }
    }

    @Test
    fun `grayscale matches OpenScan`() {
        for (c in cases) {
            val b = c.bytes("rgba")
            ImageFilterUtils.grayscale(b)
            c.assertBytes("grayscale", b)
        }
    }

    @Test
    fun `contrast matches OpenScan`() {
        for (c in cases) {
            for (a in listOf(-1.0, -0.4, 0.0, 0.25, 0.99, 1.0)) {
                val b = c.bytes("rgba")
                ImageFilterUtils.contrast(b, a)
                c.assertBytes("contrast_$a", b)
            }
        }
    }
}
