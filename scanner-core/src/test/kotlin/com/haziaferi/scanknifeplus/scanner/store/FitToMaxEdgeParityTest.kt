package com.haziaferi.scanknifeplus.scanner.store

import com.haziaferi.scanknifeplus.scanner.ParityFixture
import com.haziaferi.scanknifeplus.scanner.cv.RgbaImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compares StoredImage.fitToMaxEdge with OpenScan's compress.dart fitToMaxEdge (fixture: parity/store.tsv). */
class FitToMaxEdgeParityTest {
    private val cases = ParityFixture.load("store.tsv")

    @Test
    fun `fixture covers the expected cases`() {
        assertEquals(9, cases.size)
        assertTrue(cases.any { it.raw("same") == "true" })
    }

    @Test
    fun `fitToMaxEdge matches OpenScan`() {
        for (c in cases) {
            val src = RgbaImage(c.int("width"), c.int("height"), c.bytes("rgba"))
            val maxEdge = c.raw("max_edge").takeIf { it != "null" }?.toInt()
            // The generator records "throws" if OpenScan ever throws; no case does today.
            assertTrue("$c: OpenScan threw", c.raw("out_size") != "throws")
            val (w, h) = c.raw("out_size").split(',').map { it.toInt() }
            if (w == 0 || h == 0) {
                // OpenScan returns an empty image here (a 400:1 aspect), and image 4.2.0 even encodes it, so OpenScan would store a 100x0 page. An
                // RgbaImage cannot be empty: the port refuses, and the edit fails with the page left as it was.
                val thrown = runCatching { StoredImage.fitToMaxEdge(src, maxEdge) }.exceptionOrNull()
                assertTrue("$c: should refuse", thrown is IllegalArgumentException)
                continue
            }
            val out = StoredImage.fitToMaxEdge(src, maxEdge)
            if (c.raw("same") == "true") assertSame("$c: unchanged image is returned as is", src, out)
            assertEquals("$c: size", "$w,$h", "${out.width},${out.height}")
            c.assertBytes("out", out.pixels)
        }
    }
}
