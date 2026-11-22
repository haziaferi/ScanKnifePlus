package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
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

    @Test
    fun `PDF to ZIP opens a protected file with its password and writes each page as a PDF of its own, in order`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val source = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 3), "zipped.pdf")
        val output = File(context.cacheDir, "zipped.zip")
        val heard = mutableListOf<Int>()
        zipPages(context, source, TestPdfs.USER_PASSWORD, "Report.pdf", Uri.fromFile(output)) { heard += it }
        assertEquals(listOf(1, 2, 3), heard)
        val entries = ZipInputStream(output.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name to zip.readBytes() }.toList()
        }
        assertEquals(listOf("Report_p1.pdf", "Report_p2.pdf", "Report_p3.pdf"), entries.map { it.first })
        entries.forEachIndexed { i, (_, bytes) ->
            PDDocument.load(bytes).use { page ->
                assertFalse(page.isEncrypted)
                assertEquals(1, page.numberOfPages)
                // TestPdfs draws page n's rectangle at y = 700 - (n - 1), so each entry holds its own page.
                assertTrue(String(page.getPage(0).contents.use { it.readBytes() }, Charsets.ISO_8859_1).contains("72 ${700 - i} 100 20 re"))
            }
        }
    }
}
