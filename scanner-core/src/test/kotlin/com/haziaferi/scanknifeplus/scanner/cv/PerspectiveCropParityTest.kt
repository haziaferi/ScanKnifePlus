package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.ParityFixture
import com.haziaferi.scanknifeplus.scanner.dartRound
import com.haziaferi.scanknifeplus.scanner.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compares the perspective crop, its resize/rotate helpers and quad mapping with OpenScan's Dart output (fixture: parity/crop.tsv). */
class PerspectiveCropParityTest {
    private val cases = ParityFixture.load("crop.tsv")
    private val sources = cases.filter { it.name.startsWith("source_") }.associate { c ->
        val w = c.int("width")
        val h = c.int("height")
        val image = RgbaImage(w, h, texturedRgba(w, h))
        assertEquals("$c: rebuilt source", c.raw("rgba_sha256"), sha256Hex(image.pixels))
        c.name to image
    }
    private val warpCases = cases.filter { it.has("quad") && it.has("source") }

    @Test
    fun `fixture covers the expected cases`() {
        assertEquals(2, sources.size)
        assertEquals(12, warpCases.size)
        assertTrue(warpCases.any { it.raw("output_size") == "throws" })
        assertTrue(warpCases.any { it.has("rotate_1") })
    }

    @Test
    fun `outputSize matches OpenScan`() {
        for (c in warpCases) {
            val expected = c.raw("output_size")
            val actual = runCatching { PerspectiveCrop.outputSize(c.quad("quad")!!) }
            if (expected == "throws") {
                assertTrue("$c: should throw", actual.isFailure)
            } else {
                assertEquals("$c", expected, actual.getOrThrow().let { "${it.width},${it.height}" })
            }
        }
    }

    @Test
    fun `warpToPage matches OpenScan at every size cap`() {
        for (c in warpCases) {
            val source = sources.getValue(c.raw("source"))
            val quad = c.quad("quad")!!
            val natural = maxOf(source.width, source.height)
            for ((label, maxEdge) in listOf(
                "none" to null,
                "huge" to 100000,
                "mild" to dartRound(natural * 0.7),
                "half" to natural / 2,
                "strong" to dartRound(natural * 0.3),
            )) {
                val key = "warp_$label"
                val result = runCatching { PerspectiveCrop.warpToPage(source, quad, maxEdge) }
                when (c.raw(key).takeIf { it == "throws" || it == "null" }) {
                    "throws" -> assertTrue("$c: $key should throw", result.isFailure)
                    "null" -> assertNull("$c: $key", result.getOrThrow())
                    else -> {
                        val out = result.getOrThrow()!!
                        assertEquals("$c: ${key}_size", c.raw("${key}_size"), "${out.width},${out.height}")
                        c.assertBytes(key, out.pixels)
                    }
                }
            }
        }
    }

    @Test
    fun `cropToPage rotation matches OpenScan`() {
        val c = warpCases.first { it.has("rotate_1") }
        val source = sources.getValue(c.raw("source"))
        for (turns in listOf(1, 2, 3, -1, 5)) {
            val out = PerspectiveCrop.cropToPage(source, c.quad("quad")!!, turns)!!
            assertEquals("$c: rotate_${turns}_size", c.raw("rotate_${turns}_size"), "${out.width},${out.height}")
            c.assertBytes("rotate_$turns", out.pixels)
        }
    }

    @Test
    fun `averageResize matches the image package`() {
        for (c in cases.filter { it.name.endsWith("_resize") }) {
            val source = sources.getValue(c.raw("source"))
            val sizes = listOf(
                source.width / 2 to source.height / 2,
                source.width / 3 to source.height / 3 + 1,
                37 to 23,
                1 to 1,
                source.width to source.height,
                source.width + 10 to source.height + 10,
            )
            for ((w, h) in sizes) {
                c.assertBytes("resize_${w}x$h", PerspectiveCrop.averageResize(source, w, h).pixels)
            }
        }
    }

    @Test
    fun `quadInPixelsOf matches OpenScan`() {
        for (c in cases.filter { it.name.startsWith("normalized_") }) {
            val n = c.quad("quad")!!
            assertEquals("$c: landscape", c.raw("pixels_landscape"), PerspectiveCrop.quadInPixelsOf(n, 400, 300).format())
            assertEquals("$c: portrait", c.raw("pixels_portrait"), PerspectiveCrop.quadInPixelsOf(n, 300, 400).format())
            assertEquals("$c: square", c.raw("pixels_square"), PerspectiveCrop.quadInPixelsOf(n, 256, 256).format())
        }
    }

    companion object {
        /** Same formula as the Dart generator's `texturedRgba`. */
        fun texturedRgba(w: Int, h: Int): ByteArray {
            val out = ByteArray(w * h * 4)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = (y * w + x) * 4
                    out[i] = (x * 255 / maxOf(1, w - 1)).toByte()
                    out[i + 1] = (y * 255 / maxOf(1, h - 1)).toByte()
                    out[i + 2] = ((x * 7 + y * 13) % 256).toByte()
                    out[i + 3] = 255.toByte()
                }
            }
            return out
        }
    }
}
