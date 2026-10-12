package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

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

/** What the tool screens toast for a [PdfAccess.Unreadable] file before returning to file selection. */
const val UNREADABLE_PDF_MESSAGE = "Error: Cannot read this PDF"

/**
 * Opens [uri] once without a password to classify it. A file with only an owner password (permission restrictions, no open password) is
 * [PdfAccess.Open], so tools do not ask for a password it does not need; Unlock and Protect pass [countOwnerPassword] to see it as
 * [PdfAccess.Encrypted]. Parsing spills to temp files, so large PDFs do not exhaust the heap.
 */
suspend fun inspectPdf(context: Context, uri: Uri, countOwnerPassword: Boolean = false): PdfAccess = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly()).use {
                if (it.isEncrypted && countOwnerPassword) PdfAccess.Encrypted else PdfAccess.Open
            }
        } ?: PdfAccess.Unreadable
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

/** True when a tool must ask for a password: the file is [PdfAccess.Encrypted] and the caller was not handed one (e.g. by the preview). */
fun needsUnlockPrompt(access: PdfAccess, password: String?): Boolean = access == PdfAccess.Encrypted && password == null
