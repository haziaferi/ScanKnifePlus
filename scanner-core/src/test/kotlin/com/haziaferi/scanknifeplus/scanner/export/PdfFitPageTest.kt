package com.haziaferi.scanknifeplus.scanner.export

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Fit page size, which OpenScan does not have: each page takes its image's shape and A4's long edge, and the image fills it. */
class PdfFitPageTest {
    private val a4Long = PdfPageSize.A4.height

    @Test
    fun `a portrait image gets a portrait page with A4's height`() {
        assertEquals(PdfPageBox(a4Long * 1000 / 2000, a4Long), PdfPageSize.FIT.pageFor(1000, 2000))
    }

    @Test
    fun `a landscape image gets a landscape page with A4's height as its width`() {
        val page = PdfPageSize.FIT.pageFor(3000, 1000)
        assertEquals(PdfPageBox(a4Long, a4Long * 1000 / 3000), page)
        assertEquals(3.0, page.width / page.height, 1e-12)
    }

    @Test
    fun `a square image gets a square page`() {
        assertEquals(PdfPageBox(a4Long, a4Long), PdfPageSize.FIT.pageFor(500, 500))
    }

    @Test
    fun `the image fills a Fit page with no margin`() {
        for ((w, h) in listOf(1000 to 2000, 3000 to 1000, 7 to 7, 1 to 4000)) {
            val page = PdfPageSize.FIT.pageFor(w, h)
            assertEquals(PdfImageRect(0.0, 0.0, page.width, page.height), PdfLayout.imageRect(PdfPageSize.FIT, w, h))
        }
    }

    @Test
    fun `the fixed sizes ignore the image`() {
        for (size in listOf(PdfPageSize.A4, PdfPageSize.LETTER, PdfPageSize.LEGAL)) {
            for ((w, h) in listOf(1000 to 2000, 3000 to 1000)) assertEquals(PdfPageBox(size.width, size.height), size.pageFor(w, h))
        }
    }
}
