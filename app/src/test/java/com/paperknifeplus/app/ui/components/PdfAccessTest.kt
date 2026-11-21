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
import java.io.File

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
        assertEquals(PdfAccess.Open, inspect(TestPdfs.ownerOnly()))
    }

    @Test
    fun `counting owner passwords, as Unlock and Protect do, an owner-only PDF is encrypted`() {
        fun inspectCounting(bytes: ByteArray) =
            runBlocking { inspectPdf(context, TestPdfs.write(context.cacheDir, bytes), countOwnerPassword = true) }
        assertEquals(PdfAccess.Encrypted, inspectCounting(TestPdfs.ownerOnly()))
        assertEquals(PdfAccess.Encrypted, inspectCounting(TestPdfs.userPassword()))
        assertEquals(PdfAccess.Open, inspectCounting(TestPdfs.plain()))
    }

    @Test
    fun `garbage, a missing file and a provider without a stream are unreadable, not encrypted`() {
        assertEquals(PdfAccess.Unreadable, inspect(TestPdfs.garbage))
        assertEquals(PdfAccess.Unreadable, runBlocking { inspectPdf(context, android.net.Uri.fromFile(context.cacheDir.resolve("none.pdf"))) })
        assertEquals(PdfAccess.Unreadable, runBlocking { inspectPdf(context, TestPdfs.nullStreamUri()) })
    }

    @Test
    fun `an unchecked exception while parsing is unreadable, not a crash`() {
        val uri = android.net.Uri.parse("content://com.example.throwing/doc.pdf")
        val throwing = object : java.io.InputStream() {
            override fun read(): Int = throw IllegalStateException("malformed")
        }
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(uri, throwing)
        assertEquals(PdfAccess.Unreadable, runBlocking { inspectPdf(context, uri) })
    }

    @Test
    fun `only an encrypted file without a password needs the prompt`() {
        assertTrue(needsUnlockPrompt(PdfAccess.Encrypted, null))
        assertFalse(needsUnlockPrompt(PdfAccess.Encrypted, "pw"))
        assertFalse(needsUnlockPrompt(PdfAccess.Open, null))
        assertFalse(needsUnlockPrompt(PdfAccess.Unreadable, null))
    }

    private val locked by lazy { TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "checked.pdf") }
    private val damaged by lazy { TestPdfs.write(context.cacheDir, TestPdfs.damagedProtected(), "checked-damaged.pdf") }
    private val withoutPages by lazy { TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 0), "checked-empty.pdf") }
    private val huge by lazy { TestPdfs.OutOfMemorySource(context, TestPdfs.userPassword(pages = 2)).apply { runOutOfMemory() }.uri }

    @Test
    fun `a password check tells a wrong password from a damaged, empty or too large file`() = runBlocking {
        assertEquals(PasswordCheck.Opened(2), checkPageCount(context, locked, TestPdfs.USER_PASSWORD))
        assertEquals(PasswordCheck.WrongPassword, checkPageCount(context, locked, "wrong"))
        assertEquals(PasswordCheck.Unreadable, checkPageCount(context, damaged, TestPdfs.USER_PASSWORD))
        assertEquals(PasswordCheck.Unreadable, checkPageCount(context, withoutPages, TestPdfs.USER_PASSWORD))
        assertEquals(PasswordCheck.Unreadable, checkPageCount(context, huge, TestPdfs.USER_PASSWORD))
    }

    @Test
    fun `decrypting tells them apart the same way, and keeps a copy only of a file it opened`() = runBlocking {
        val opened = (decryptToCache(context, locked, TestPdfs.USER_PASSWORD) as PasswordCheck.Opened).value
        assertEquals(2, opened.pageCount)
        assertEquals(PasswordCheck.WrongPassword, decryptToCache(context, locked, "wrong"))
        assertEquals(PasswordCheck.Unreadable, decryptToCache(context, damaged, TestPdfs.USER_PASSWORD))
        assertEquals(PasswordCheck.Unreadable, decryptToCache(context, withoutPages, TestPdfs.USER_PASSWORD))
        assertEquals(PasswordCheck.Unreadable, decryptToCache(context, huge, TestPdfs.USER_PASSWORD))
        val copies = context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("decrypted_") }.map { it.name }
        assertEquals(listOf(File(opened.uri.path!!).name), copies)
    }
}
