package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** What opening a PDF without a password showed, as [inspectPdf] reports it. */
sealed interface PdfAccess {
    /** Opens and is not encrypted. */
    data object Open : PdfAccess

    /** Needs a password, or opens with an empty one but carries an owner password; the tool screens ask for a password either way. */
    data object Encrypted : PdfAccess

    /** Missing, not a PDF, or too large to parse. */
    data object Unreadable : PdfAccess
}

private const val TAG = "PdfAccess"

/** What the tool screens toast for a [PdfAccess.Unreadable] file before returning to file selection. */
const val UNREADABLE_PDF_MESSAGE = "Error: Cannot read this PDF"

/** Opens [uri] once without a password to classify it. Parsing spills to temp files, so large PDFs do not exhaust the heap. */
suspend fun inspectPdf(context: Context, uri: Uri): PdfAccess = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly()).use { if (it.isEncrypted) PdfAccess.Encrypted else PdfAccess.Open }
        } ?: PdfAccess.Unreadable
    } catch (e: InvalidPasswordException) {
        PdfAccess.Encrypted
    } catch (e: IOException) {
        Log.w(TAG, "Cannot read $uri", e)
        PdfAccess.Unreadable
    } catch (e: SecurityException) {
        // A provider that revoked or never granted read access; the screen reports it like any unreadable file.
        Log.w(TAG, "No read access to $uri", e)
        PdfAccess.Unreadable
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory reading $uri", e)
        PdfAccess.Unreadable
    }
}

/** True when a tool must ask for a password: the file is [PdfAccess.Encrypted] and the caller was not handed one (e.g. by the preview). */
fun needsUnlockPrompt(access: PdfAccess, password: String?): Boolean = access == PdfAccess.Encrypted && password == null
