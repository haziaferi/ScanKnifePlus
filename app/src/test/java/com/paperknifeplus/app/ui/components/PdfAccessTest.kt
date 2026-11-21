package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.data.image.PdDocumentPool
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    private val locked by lazy { TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "checked.pdf") }
    private val damaged by lazy { TestPdfs.write(context.cacheDir, TestPdfs.damagedProtected(), "checked-damaged.pdf") }
    private val withoutPages by lazy { TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 0), "checked-empty.pdf") }
    private val huge by lazy {
        TestPdfs.OutOfMemorySource(context, TestPdfs.userPassword(pages = 2), "huge-checked.pdf").apply { runOutOfMemory() }.uri
    }

    @Test
    fun `a password check tells a wrong password from a damaged, empty or too large file`() = runBlocking {
        assertEquals(PasswordCheck.Opened(2), checkPageCount(context, locked, TestPdfs.USER_PASSWORD))
        assertEquals(PasswordCheck.WrongPassword, checkPageCount(context, locked, "wrong"))
        assertEquals(PasswordCheck.Unreadable, checkPageCount(context, damaged, TestPdfs.USER_PASSWORD))
        assertEquals(PasswordCheck.Unreadable, checkPageCount(context, withoutPages, TestPdfs.USER_PASSWORD))
        assertEquals(PasswordCheck.Unreadable, checkPageCount(context, huge, TestPdfs.USER_PASSWORD))
    }

    @Test
    fun `only a page count check that opens the file keeps its document in the pool`() = runBlocking {
        // A pooled document gives the page count again without opening the file.
        val opened = TestPdfs.CountedSource(context, TestPdfs.userPassword(pages = 2), "counted-opened.pdf")
        assertEquals(PasswordCheck.Opened(2), checkPageCount(context, opened.uri, TestPdfs.USER_PASSWORD))
        assertEquals(2, getPageCount(context, opened.uri, TestPdfs.USER_PASSWORD))
        assertEquals(1, opened.opens)

        // It opens, and has no pages: the check parsed it, finds it unreadable and closes it, so the next count parses it again.
        val empty = TestPdfs.CountedSource(context, TestPdfs.userPassword(pages = 0), "counted-empty.pdf")
        assertEquals(PasswordCheck.Unreadable, checkPageCount(context, empty.uri, TestPdfs.USER_PASSWORD))
        getPageCount(context, empty.uri, TestPdfs.USER_PASSWORD)
        assertEquals(2, empty.opens)
    }

    @Test
    fun `a stopped page count check leaves nothing in the pool`() = runBlocking {
        val source = TestPdfs.HeldSource(context, TestPdfs.userPassword(pages = 2), "held-stopped-check.pdf")
        source.hold()
        val check = launch(Dispatchers.Default) { checkPageCount(context, source.uri, TestPdfs.USER_PASSWORD) }
        assertTrue(source.reached.await(10, TimeUnit.SECONDS))
        check.cancel()
        // The parse goes on to the end, then the check finds itself stopped.
        source.release()
        withTimeout(10_000) { check.join() }

        assertEquals(2, getPageCount(context, source.uri, TestPdfs.USER_PASSWORD))
        assertEquals("read again, not from the pool", 2, source.opens)
    }

    @Test
    fun `a stopped check leaves its document to a check of the same file that waits for it`() = runBlocking {
        val source = TestPdfs.HeldSource(context, TestPdfs.userPassword(pages = 2), "held-checked-twice.pdf")
        source.hold()
        val first = launch(Dispatchers.Default) { checkPageCount(context, source.uri, TestPdfs.USER_PASSWORD) }
        assertTrue(source.reached.await(10, TimeUnit.SECONDS))
        first.cancel()
        // The same file and password again, as when the file is picked once more: this check waits for the first one's parse.
        val second = async(Dispatchers.Default) { checkPageCount(context, source.uri, TestPdfs.USER_PASSWORD) }
        withTimeout(10_000) { while (PdDocumentPool.waiters(source.uri, TestPdfs.USER_PASSWORD) == 0) delay(10) }
        source.release()

        assertEquals(PasswordCheck.Opened(2), withTimeout(10_000) { second.await() })
        withTimeout(10_000) { first.join() }
        assertEquals(2, getPageCount(context, source.uri, TestPdfs.USER_PASSWORD))
        assertEquals("parsed once, then read from the pool", 1, source.opens)
    }

    @Test
    fun `a check that finds the file unreadable leaves a document another caller loaded`() = runBlocking {
        val empty = TestPdfs.CountedSource(context, TestPdfs.userPassword(pages = 0), "counted-empty-shared.pdf")
        // Loaded by another caller, as a page thumbnail would: it opens, and has no pages.
        assertEquals(0, getPageCount(context, empty.uri, TestPdfs.USER_PASSWORD))
        assertEquals(PasswordCheck.Unreadable, checkPageCount(context, empty.uri, TestPdfs.USER_PASSWORD))
        getPageCount(context, empty.uri, TestPdfs.USER_PASSWORD)
        assertEquals("still pooled", 1, empty.opens)
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

    @Test
    fun `a copy written before the file turns out unreadable is deleted`() = runBlocking {
        // A provider whose stream fails as it is closed, which is after the copy was written.
        val uri = android.net.Uri.parse("content://com.paperknifeplus.app.test.closing/decrypted-then-failed.pdf")
        val bytes = TestPdfs.userPassword(pages = 2)
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri) {
            object : FilterInputStream(bytes.inputStream()) {
                override fun close() {
                    super.close()
                    throw IOException("closing failed")
                }
            }
        }
        assertEquals(PasswordCheck.Unreadable, decryptToCache(context, uri, TestPdfs.USER_PASSWORD))
        assertEquals(emptyList<String>(), context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("decrypted_") }.map { it.name })
    }
}
