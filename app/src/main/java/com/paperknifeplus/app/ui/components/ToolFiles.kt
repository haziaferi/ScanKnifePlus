package com.paperknifeplus.app.ui.components

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.haziaferi.scanknifeplus.storage.SavedFiles
import com.paperknifeplus.app.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

private const val TAG = "ToolFiles"

/** Decrypted copies written by [decryptToCache], and the legacy `preview_*.pdf` temp files, both directly in the cache folder. */
private val DECRYPTED_COPY = Regex("""decrypted_[^/\\]*\.pdf""")
private val LEGACY_PREVIEW = Regex("""preview_[^/\\]*\.pdf""")

/** [name] without a trailing ".pdf" (any case), for suggesting output names; ".pdf" elsewhere in the name stays. */
fun pdfBaseName(name: String): String = if (name.endsWith(".pdf", ignoreCase = true)) name.dropLast(4) else name

/** Opens [uri] for reading, throwing [IOException] where [ContentResolver.openInputStream] would return null. */
fun ContentResolver.requireInputStream(uri: Uri): InputStream = openInputStream(uri) ?: throw IOException("Cannot read $uri")

/**
 * Opens [uri] for writing, throwing [IOException] where [ContentResolver.openOutputStream] would return null. The default "wt" truncates, in
 * case the picker handed back an existing file; providers that do not support it fall back to "w", as in ScanExport.save.
 */
fun ContentResolver.requireOutputStream(uri: Uri, mode: String = "wt"): OutputStream {
    val stream = try {
        openOutputStream(uri, mode)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    }
    return stream ?: (if (mode == "wt") openOutputStream(uri, "w") else null) ?: throw IOException("Cannot write $uri")
}

/**
 * Whether a tool shows [e] as its failure and carries on, rather than crashing: an exception, or running out of memory on a file too large
 * for the heap. Cancellation is an exception too, so catch it first.
 */
fun isToolFailure(e: Throwable): Boolean = e is Exception || e is OutOfMemoryError

/** The toast for a tool failure [e] ([isToolFailure]): "Error: " and its message, in the app's own words when it ran out of memory. */
fun toolFailureText(context: Context, e: Throwable): String =
    toolText(context, R.string.tool_error, if (e is OutOfMemoryError) toolText(context, R.string.tool_out_of_memory) else e.message)

/**
 * Removes the file a save created for an output that then failed or was cancelled, wherever its [SaveTarget] made it: in the app, in the
 * user's folder or with the system picker. One already listed is a finished save (a cancel that came after it was listed) and stays. Never
 * throws. Blocking.
 */
fun deleteCreatedDocument(context: Context, uri: Uri) {
    try {
        val files = SavedFiles.of(context)
        if (files.listed(uri)) return
        if (files.fileOf(uri) != null) {
            files.discard(uri)
        } else if (DocumentsContract.isTreeUri(uri) || DocumentsContract.isDocumentUri(context, uri)) {
            // A document of the user's folder is named through its tree, which isDocumentUri accepts only for a provider it can resolve.
            DocumentsContract.deleteDocument(context.contentResolver, uri)
            files.discard(uri) // its unfinished row, if a save into the folder made it
        }
    } catch (e: Exception) {
        // Providers throw anything from FileNotFoundException to UnsupportedOperationException here; a leftover empty file is all that is lost.
        Log.w(TAG, "Could not remove the unfinished $uri", e)
    }
}

/**
 * Deletes [uri] only if it is a decrypted copy from [decryptToCache]: a file:// URI directly in the cache folder named `decrypted_*.pdf`.
 * Any other URI, including the file the user picked, is left alone. Returns whether a file was deleted.
 */
fun deleteDecryptedCopy(context: Context, uri: Uri?): Boolean {
    if (uri == null || uri.scheme != ContentResolver.SCHEME_FILE) return false
    val file = File(uri.path ?: return false).canonicalFile
    if (file.parentFile != context.cacheDir.canonicalFile || !DECRYPTED_COPY.matches(file.name)) return false
    return file.delete()
}

/**
 * Deletes decrypted copies and legacy preview files left directly in [cacheDir] by earlier sessions, those last modified at least [minAgeMs]
 * before [now]; subfolders are not touched.
 */
fun sweepDecryptedCopies(cacheDir: File, minAgeMs: Long = 0, now: Long = System.currentTimeMillis()): Int =
    cacheDir.listFiles().orEmpty().count {
        it.isFile && (DECRYPTED_COPY.matches(it.name) || LEGACY_PREVIEW.matches(it.name)) && now - it.lastModified() >= minAgeMs && it.delete()
    }

/** Seconds between [startMs] and [endMs] as the tool screens show it, e.g. "1.5s", in the default locale as before. */
fun formatElapsed(startMs: Long, endMs: Long = System.currentTimeMillis()): String =
    String.format(Locale.getDefault(), "%.1fs", (endMs - startMs) / 1000.0)

/** The MIME type of a History entry's output; entries whose provider does not say are PDFs, as every tool but the ZIP exports writes. */
fun historyEntryMime(resolver: ContentResolver, uri: Uri): String = resolver.getType(uri) ?: "application/pdf"

/** This build's version name, e.g. "2.0". */
fun appVersionName(context: Context): String =
    @Suppress("DEPRECATION") // The PackageInfoFlags overload needs API 33.
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
