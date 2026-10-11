package com.paperknifeplus.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ZipEntryNameTest {
    @Test
    fun `path segments from the provider name are dropped`() {
        assertEquals("evil_p1.pdf", zipPageEntryName("../evil.pdf", 1))
        assertEquals("b_p1.pdf", zipPageEntryName("a\\b.pdf", 1))
        assertEquals("x_p2.pdf", zipPageEntryName("/sdcard/Download/x.PDF", 2))
    }

    @Test
    fun `names that are no file name fall back to Document`() {
        assertEquals("Document_p1.pdf", zipPageEntryName("..", 1))
        assertEquals("Document_p1.pdf", zipPageEntryName(".", 1))
        assertEquals("Document_p1.pdf", zipPageEntryName("", 1))
        assertEquals("Document_p3.pdf", zipPageEntryName("dir/", 3))
    }

    @Test
    fun `colons and control characters are replaced`() {
        assertEquals("C_report_p1.pdf", zipPageEntryName("C:report.pdf", 1))
        assertEquals("a_b_p1.pdf", zipPageEntryName("a:b.pdf", 1))
        assertEquals("a_b_p1.pdf", zipPageEntryName("a\u0000b.pdf", 1))
    }

    @Test
    fun `ordinary names are unchanged and only a trailing pdf is stripped`() {
        assertEquals("Report 2024_p12.pdf", zipPageEntryName("Report 2024.pdf", 12))
        assertEquals("x.pdf.v2_p1.pdf", zipPageEntryName("x.pdf.v2.pdf", 1))
        assertEquals("notes.txt_p1.pdf", zipPageEntryName("notes.txt", 1))
    }
}
