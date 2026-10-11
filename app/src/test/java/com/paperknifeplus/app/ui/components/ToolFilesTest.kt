package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class ToolFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `pdfBaseName strips only a trailing pdf extension`() {
        assertEquals("Scan", pdfBaseName("Scan.PDF"))
        assertEquals("a.pdf", pdfBaseName("a.pdf.pdf"))
        assertEquals("my.pdf notes", pdfBaseName("my.pdf notes.pdf"))
        assertEquals("noext", pdfBaseName("noext"))
    }

    @Test
    fun `a provider without a stream throws IOException instead of returning null`() {
        val uri = TestPdfs.nullStreamUri()
        assertThrows(IOException::class.java) { context.contentResolver.requireInputStream(uri) }
        assertThrows(IOException::class.java) { context.contentResolver.requireOutputStream(uri) }
    }

    @Test
    fun `deleteDecryptedCopy deletes only decrypted copies directly in the cache folder`() {
        val cache = context.cacheDir
        val copy = File(cache, "decrypted_123.pdf").apply { writeText("x") }
        val userFile = File(cache, "report.pdf").apply { writeText("x") }
        val nested = File(cache, "scan-staging/decrypted_1.pdf").apply { parentFile!!.mkdirs(); writeText("x") }
        val outside = File(context.filesDir, "decrypted_1.pdf").apply { parentFile!!.mkdirs(); writeText("x") }

        assertFalse(deleteDecryptedCopy(context, null))
        assertFalse(deleteDecryptedCopy(context, Uri.parse("content://com.example/decrypted_123.pdf")))
        assertFalse(deleteDecryptedCopy(context, Uri.fromFile(userFile)))
        assertFalse(deleteDecryptedCopy(context, Uri.fromFile(nested)))
        assertFalse(deleteDecryptedCopy(context, Uri.fromFile(outside)))
        assertFalse(deleteDecryptedCopy(context, Uri.fromFile(File(cache, "scan-staging/../../decrypted_123.pdf"))))
        assertTrue(listOf(userFile, nested, outside, copy).all { it.exists() })

        assertTrue(deleteDecryptedCopy(context, Uri.fromFile(copy)))
        assertFalse(copy.exists())
    }

    @Test
    fun `sweep removes top-level decrypted and preview copies and keeps everything else`() {
        val cache = File(context.cacheDir, "sweep").apply { deleteRecursively(); mkdirs() }
        val swept = listOf("decrypted_1.pdf", "decrypted_2.pdf", "preview_3.pdf").map { File(cache, it).apply { writeText("x") } }
        val kept = listOf(
            "pdf_previews/decrypted_9.pdf", "scan-staging/page.jpg", "shared/pdf-1/scan.pdf", "scan-export/out.pdf",
            "notes.pdf", "decrypted_1.txt", "my_preview_1.pdf"
        ).map { File(cache, it).apply { parentFile!!.mkdirs(); writeText("x") } }

        assertEquals(3, sweepDecryptedCopies(cache))
        assertTrue(swept.none { it.exists() })
        assertTrue(kept.all { it.exists() })
    }

    @Test
    fun `formatElapsed keeps the one-decimal seconds text`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("1.5s", formatElapsed(1_000, 2_500))
            assertEquals("0.0s", formatElapsed(5, 5))
            Locale.setDefault(Locale.GERMANY)
            assertEquals("12,3s", formatElapsed(0, 12_340))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `history entries without a provider type count as PDFs`() {
        assertEquals("application/pdf", historyEntryMime(context.contentResolver, Uri.fromFile(File(context.cacheDir, "x.zip"))))
    }

    @Test
    fun `the version name comes from the package`() {
        assertEquals("1.1", appVersionName(context))
    }
}
