package com.haziaferi.scanknifeplus.scanner.store

import com.haziaferi.scanknifeplus.scanner.cv.PageSize
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values follow OpenScan's _fitted, _decodeForPage and flattenOntoWhite (native_decode.dart, file_operations.dart at 841d25a). */
class StoredImageTest {
    @Test
    fun `constants match OpenScan`() {
        assertEquals(2400, StoredImage.PAGE_MAX_EDGE)
        assertEquals(85, StoredImage.PAGE_QUALITY)
        assertEquals(3200, StoredImage.ORIGINAL_MAX_EDGE)
        assertEquals(80, StoredImage.ORIGINAL_QUALITY)
    }

    @Test
    fun `fitted scales the long edge down to the cap and never up`() {
        assertEquals(PageSize(2400, 1800), StoredImage.fitted(4000, 3000, 2400))
        assertEquals(PageSize(1800, 2400), StoredImage.fitted(3000, 4000, 2400))
        assertEquals(PageSize(1000, 700), StoredImage.fitted(1000, 700, 2400))
        assertEquals(PageSize(2400, 2400), StoredImage.fitted(2400, 2400, 2400))
        // 4032x3024 (a common 12 MP sensor) at the 3200 cap: 3024 * 3200 / 4032 = 2400 exactly.
        assertEquals(PageSize(3200, 2400), StoredImage.fitted(4032, 3024, 3200))
        // A cap of 0 or less means "keep the size".
        assertEquals(PageSize(4000, 3000), StoredImage.fitted(4000, 3000, 0))
    }

    @Test
    fun `fitted rounds ties away from zero and keeps at least one pixel`() {
        // 3 * 2400 / 4800 = 1.5 rounds to 2 (Dart round), not 1.
        assertEquals(PageSize(2400, 2), StoredImage.fitted(4800, 3, 2400))
        // 1 * 2400 / 100000 = 0.024 rounds to 0, kept at 1.
        assertEquals(PageSize(2400, 1), StoredImage.fitted(100_000, 1, 2400))
    }

    @Test
    fun `without a quad a page decodes at the page cap`() {
        assertEquals(2400, StoredImage.pageDecodeMaxEdge(4032, 3024, null))
    }

    @Test
    fun `a quad within the cap decodes the capture whole`() {
        // A centred quad spanning half of each side of a 3024x4032 portrait photo: natural size 1512x2016, within 2400.
        val quad = Quad(Pt(0.25, 0.25), Pt(0.75, 0.25), Pt(0.75, 0.75), Pt(0.25, 0.75))
        assertEquals(4032, StoredImage.pageDecodeMaxEdge(3024, 4032, quad))
    }

    @Test
    fun `a quad larger than the cap scales the decode so the quad lands on it`() {
        // The whole 3024x4032 frame: natural 3024x4032, so decode at 4032 * 2400 / 4032 = 2400.
        val whole = Quad(Pt(0.0, 0.0), Pt(1.0, 0.0), Pt(1.0, 1.0), Pt(0.0, 1.0))
        assertEquals(2400, StoredImage.pageDecodeMaxEdge(3024, 4032, whole))
        // 90% of each side of 4000x3000 (landscape: the quad is rotated back first): natural longest 3600, decode at 4000 * 2400 / 3600 = 2666.67.
        val inner = Quad(Pt(0.05, 0.05), Pt(0.95, 0.05), Pt(0.95, 0.95), Pt(0.05, 0.95))
        assertEquals(2667, StoredImage.pageDecodeMaxEdge(4000, 3000, inner))
    }

    @Test
    fun `flattenOntoWhite composites premultiplied pixels over white and leaves opaque ones alone`() {
        val rgba = byteArrayOf(
            10, 20, 30, -1, // opaque: unchanged
            0, 0, 0, 0, // fully transparent: white
            64, 32, 0, 128.toByte(), // half transparent, premultiplied: + 127 each
        )
        StoredImage.flattenOntoWhite(rgba)
        assertArrayEquals(
            byteArrayOf(10, 20, 30, -1, -1, -1, -1, -1, 191.toByte(), (159).toByte(), 127, -1),
            rgba,
        )
    }
}
