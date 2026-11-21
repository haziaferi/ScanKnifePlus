package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import com.paperknifeplus.app.data.image.PdDocumentPool
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What opening a PDF without a password showed, as [inspectPdf] reports it. */
sealed interface PdfAccess {
    /** Opens without a password: not encrypted, or (unless [inspectPdf] counts owner passwords) protected by an owner password only. */
    data object Open : PdfAccess

    /** Needs a user password to open, or carries an owner password when [inspectPdf] was asked to count those. */
    data object Encrypted : PdfAccess

    /** Missing, not a PDF, or too large to parse. */
    data object Unreadable : PdfAccess
}

private const val TAG = "PdfAccess"

/**
 * [block] on [input] opened with [password] (none for null), parsed through temp files in the cache so that a large PDF does not exhaust
 * the heap: the opener for the tools' passes over a whole document. Merge and Split, the page renderers' document pool and Ultra Preview
 * open PDFs their own way, also through temp files; Redaction parses a PDF attached to the file in memory, from bytes it has already
 * capped. Throws on failure, InvalidPasswordException for a wrong or missing password, and OutOfMemoryError when even that is not enough.
 * Blocking; inline, so [block] can report progress on the main thread.
 */
internal inline fun <T> withPdf(context: Context, input: Uri, password: String?, block: (PDDocument) -> T): T =
    context.contentResolver.requireInputStream(input).use { stream ->
        PDDocument.load(stream, password.orEmpty(), MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)).use(block)
    }

/**
 * Opens [uri] once without a password to classify it. A file with only an owner password (permission restrictions, no open password) is
 * [PdfAccess.Open], so tools do not ask for a password it does not need; Unlock and Protect pass [countOwnerPassword] to see it as
 * [PdfAccess.Encrypted]. Parsing spills to temp files ([withPdf]), so large PDFs do not exhaust the heap.
 */
suspend fun inspectPdf(context: Context, uri: Uri, countOwnerPassword: Boolean = false): PdfAccess = withContext(Dispatchers.IO) {
    try {
        withPdf(context, uri, null) { if (it.isEncrypted && countOwnerPassword) PdfAccess.Encrypted else PdfAccess.Open }
    } catch (e: InvalidPasswordException) {
        PdfAccess.Encrypted
    } catch (e: IOException) {
        Log.w(TAG, "Cannot read $uri", e)
        PdfAccess.Unreadable
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        // PDFBox throws unchecked exceptions on some malformed files, and providers throw SecurityException without read access.
        Log.w(TAG, "Cannot read $uri", e)
        PdfAccess.Unreadable
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory reading $uri", e)
        PdfAccess.Unreadable
    }
}

/** What opening a file with a password found ([checkPassword]): what was read from it, a password that does not open it, or neither. */
sealed interface PasswordCheck<out T> {
    /** The password opens the file; [value] is what the check read from it. */
    data class Opened<out T>(val value: T) : PasswordCheck<T>

    /** The password does not open the file: the prompt says so and asks again. */
    data object WrongPassword : PasswordCheck<Nothing>

    /** Damaged, without pages, or too large to parse: the tool says it cannot be read and resets. */
    data object Unreadable : PasswordCheck<Nothing>
}

/**
 * [read] on [uri] opened with [password] (none for null) through [withPdf], off the main thread. Only a password that does not open the
 * file is [PasswordCheck.WrongPassword]; any other failure, running out of memory included, and a [read] that finds nothing (null) are
 * [PasswordCheck.Unreadable], so a damaged protected file is not taken for a wrong password. Cancellation is rethrown.
 */
suspend fun <T : Any> checkPassword(context: Context, uri: Uri, password: String?, read: (PDDocument) -> T?): PasswordCheck<T> =
    tellApart(uri) { withPdf(context, uri, password, read) }

/** The page count of [uri] opened with [password], told apart as [checkPassword] does; a file without pages is unreadable ([checkPooled]). */
suspend fun checkPageCount(context: Context, uri: Uri, password: String): PasswordCheck<Int> =
    checkPooled(context, uri, password) { document -> document.numberOfPages.takeIf { it > 0 } }

/**
 * [read] on [uri] opened with [password] through the page renderers' document pool, told apart as [checkPassword] does. The thumbnails
 * and page grid that follow a check that opened the file reuse the document it parsed. A check that is stopped, or that finds the file
 * unreadable, closes the document it parsed ([PdDocumentPool.check]), which would otherwise stay pooled with its temp file until it was the
 * least recently used; a document another caller uses stays.
 */
internal suspend fun <T : Any> checkPooled(context: Context, uri: Uri, password: String, read: (PDDocument) -> T?): PasswordCheck<T> =
    tellApart(uri) { PdDocumentPool.check(context, uri, password, read) }

/** What [read] found on [uri], told apart as [checkPassword] says. */
private suspend fun <T : Any> tellApart(uri: Uri, read: suspend () -> T?): PasswordCheck<T> = withContext(Dispatchers.IO) {
    try {
        read()?.let { PasswordCheck.Opened(it) } ?: PasswordCheck.Unreadable
    } catch (e: InvalidPasswordException) {
        PasswordCheck.WrongPassword
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read $uri", e)
        PasswordCheck.Unreadable
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory reading $uri", e)
        PasswordCheck.Unreadable
    }
}
