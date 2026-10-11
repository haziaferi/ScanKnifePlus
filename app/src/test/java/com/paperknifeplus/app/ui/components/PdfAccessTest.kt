package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PdfAccessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun inspect(bytes: ByteArray) = runBlocking { inspectPdf(context, TestPdfs.write(context.cacheDir, bytes)) }

    @Test
    fun `classifies plain, password and owner-only PDFs`() {
        assertEquals(PdfAccess.Open, inspect(TestPdfs.plain(3)))
        assertEquals(PdfAccess.Encrypted, inspect(TestPdfs.userPassword()))
        assertEquals(PdfAccess.Encrypted, inspect(TestPdfs.ownerOnly()))
    }

    @Test
    fun `garbage, a missing file and a provider without a stream are unreadable, not encrypted`() {
        assertEquals(PdfAccess.Unreadable, inspect(TestPdfs.garbage))
        assertEquals(PdfAccess.Unreadable, runBlocking { inspectPdf(context, android.net.Uri.fromFile(context.cacheDir.resolve("none.pdf"))) })
        assertEquals(PdfAccess.Unreadable, runBlocking { inspectPdf(context, TestPdfs.nullStreamUri()) })
    }

    @Test
    fun `only an encrypted file without a password needs the prompt`() {
        assertTrue(needsUnlockPrompt(PdfAccess.Encrypted, null))
        assertFalse(needsUnlockPrompt(PdfAccess.Encrypted, "pw"))
        assertFalse(needsUnlockPrompt(PdfAccess.Open, null))
        assertFalse(needsUnlockPrompt(PdfAccess.Unreadable, null))
    }
}
