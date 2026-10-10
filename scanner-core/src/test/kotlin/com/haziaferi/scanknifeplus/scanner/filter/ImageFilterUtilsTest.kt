package com.haziaferi.scanknifeplus.scanner.filter

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ImageFilterUtilsTest {
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

    // OpenScan rounds per pixel, so with no pixels an infinite factor is never rounded and nothing throws.
    @Test
    fun `contrast of an empty buffer with an infinite factor does nothing like Dart`() {
        ImageFilterUtils.contrast(ByteArray(0), 259.0 / 255)
    }
}
