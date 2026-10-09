package com.haziaferi.scanknifeplus.scanner.filter

import com.haziaferi.scanknifeplus.scanner.ParityFixture
import com.haziaferi.scanknifeplus.scanner.cv.EdgeDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Compares the six document filters and their helpers with OpenScan's Dart output (fixture: parity/filters.tsv). */
class DocumentFiltersParityTest {
    private val cases = ParityFixture.load("filters.tsv")
    private val imageCases = cases.filter { it.has("rgba") }
    private val luts = cases.single { it.name == "luts" }

    @Test
    fun `fixture covers every filter and a field-downscaling size`() {
        assertTrue(imageCases.size >= 8)
        assertTrue(imageCases.any { maxOf(it.int("width"), it.int("height")) > 640 })
        for (f in DocumentFilters.all) assertTrue(f.name, imageCases.all { it.has("filter_${f.name}") })
    }

    @Test
    fun `every filter matches OpenScan`() {
        for (c in imageCases) {
            for (f in DocumentFilters.all) {
                val b = c.bytes("rgba")
                f.apply(b, c.int("width"), c.int("height"))
                c.assertBytes("filter_${f.name}", b)
            }
        }
    }

    @Test
    fun `helpers match OpenScan`() {
        for (c in imageCases) {
            val w = c.int("width")
            val h = c.int("height")
            val rgba = c.bytes("rgba")
            val gray = EdgeDetection.rgbaToGrayscale(rgba, w, h)
            c.assertBytes("box_blur_3", DocumentFilterUtils.boxBlur(gray, w, h, 3))
            assertEquals("$c: bounds_r", c.raw("bounds_r"), DocumentFilterUtils.percentileBounds(DocumentFilterUtils.channelHistogram(rgba, 0), 0.005, 0.005).joinToString(","))
            assertEquals("$c: bounds_gray", c.raw("bounds_gray"), DocumentFilterUtils.percentileBounds(DocumentFilterUtils.grayHistogram(gray), 0.001, 0.05).joinToString(","))
            val (nw, nh) = c.raw("down_size").split(',').map { it.toInt() }
            c.assertBytes("down", DocumentFilterUtils.downscaleGray(gray, w, h, nw, nh))
        }
    }

    @Test
    fun `lookup tables, empty histogram and name lookup match OpenScan`() {
        for ((low, high) in listOf(0 to 255, 10 to 240, 100 to 101, 0 to 1, 200 to 255, 37 to 180)) {
            luts.assertBytes("lut_${low}_$high", DocumentFilterUtils.stretchLut(low, high))
        }
        assertEquals(luts.raw("bounds_empty"), DocumentFilterUtils.percentileBounds(IntArray(256), 0.01, 0.01).joinToString(","))
        val names = listOf("Original", "Auto", "Lighten", "Grayscale", "B&W", "Whiteboard", "bogus")
        assertEquals(luts.raw("by_name"), names.joinToString(",") { DocumentFilters.byName(it).name })
        assertEquals("Original", DocumentFilters.byName(null).name)
    }
}
