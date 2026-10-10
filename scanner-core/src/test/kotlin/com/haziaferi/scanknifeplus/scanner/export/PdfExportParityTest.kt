package com.haziaferi.scanknifeplus.scanner.export

import com.haziaferi.scanknifeplus.scanner.ParityFixture
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

/** Compares page placement and export names with OpenScan's createPdf and exportFileName (fixture: parity/pdf.tsv). */
class PdfExportParityTest {
    private val cases = ParityFixture.load("pdf.tsv")
    private val layouts = cases.filter { it.has("draw") }
    private val names = cases.filter { it.has("export_name") }

    @Test
    fun `fixture covers the expected cases`() {
        assertEquals(21, layouts.size)
        assertEquals(9, names.size)
    }

    @Test
    fun `page sizes match the pdf package exactly`() {
        for (c in layouts) {
            val page = PdfPageSize.valueOf(c.raw("format").uppercase())
            assertEquals("$c: width", c.double("page_width"), page.width, 0.0)
            assertEquals("$c: height", c.double("page_height"), page.height, 0.0)
            val box = c.raw("media_box").split(' ').map { it.toDouble() }
            assertEquals("$c: media box", listOf(0.0, 0.0), box.take(2))
            assertEquals("$c: media box width", page.width, box[2], 1e-5)
            assertEquals("$c: media box height", page.height, box[3], 1e-5)
        }
    }

    @Test
    fun `images are placed where OpenScan draws them`() {
        for (c in layouts) {
            val page = PdfPageSize.valueOf(c.raw("format").uppercase())
            val rect = PdfLayout.imageRect(page, c.int("width"), c.int("height"))
            // The pdf package writes numbers with 5 decimals; the image is drawn inside a translation by the margin.
            val (mx, my) = c.raw("margin").split(',').map { it.toDouble() }
            val (w, h, x, y) = c.raw("draw").split(',').map { it.toDouble() }
            assertEquals("$c: margin", PdfLayout.MARGIN, mx, 0.0)
            assertEquals("$c: margin", PdfLayout.MARGIN, my, 0.0)
            assertEquals("$c: width", w, rect.width, 1e-5)
            assertEquals("$c: height", h, rect.height, 1e-5)
            assertEquals("$c: x", mx + x, rect.x, 1e-5)
            assertEquals("$c: y", my + y, rect.y, 1e-5)
        }
    }

    @Test
    fun `export names match OpenScan, cut to the length limit`() {
        val openScanFallback = "OpenScan-1970-01-01-0" // the timestamp name OpenScan mints when nothing is left (the fixture fixes the clock)
        for (c in names) {
            val name = String(Base64.getDecoder().decode(c.raw("name_b64")), Charsets.UTF_8)
            val expected = c.raw("export_name").take(ExportNames.MAX_LENGTH)
            assertEquals("$c: '$name'", expected, ExportNames.exportFileName(name, openScanFallback))
        }
        // Only the 300-character name is affected by the limit.
        assertEquals(1, names.count { c -> c.raw("export_name").length > ExportNames.MAX_LENGTH })
    }
}
