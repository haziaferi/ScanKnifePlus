package com.haziaferi.scanknifeplus.scan.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.library.ScanDocument
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scanner.export.ExportNames
import com.haziaferi.scanknifeplus.scanner.export.ExportQuality
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.paperknifeplus.app.ui.components.SessionManager
import com.paperknifeplus.app.ui.components.getUriDetails
import java.io.File
import java.io.IOException

/** A PDF saved where the user chose: [uri] as the system file picker returned it, holding [pages] pages. */
data class SavedPdf(val uri: Uri, val pages: Int)

/**
 * The two ways a scan leaves the app, kept apart as in OpenScan. [save] writes to a location the user picked with the system file picker
 * (ACTION_CREATE_DOCUMENT), so nothing is written to a hard-coded Downloads folder and nothing is overwritten, and records the PDF in History.
 * [share] writes a throwaway copy under `cache/shared/` for a share sheet. Both block: call off the main thread.
 */
class ScanExport(
    private val context: Context,
    private val library: ScanLibrary = ScanLibrary(ScanFiles.libraryDir(context)),
    private val exporter: ScanPdfExporter = ScanPdfExporter(library, ScanFiles.exportDir(context)),
    private val onSaved: (SavedPdf) -> Unit = { addToHistory(context, it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The file name to suggest for [document]'s PDF: its name made safe for any file system (see [ExportNames.exportFileName]). */
    fun fileName(document: ScanDocument): String = ExportNames.exportFileName(document.displayName, document.id) + ".pdf"

    /**
     * Writes document [id] (only [pageIds] if given) into [dest], a document the system file picker just created, and adds it to History.
     * Returns what was saved, or null on failure, in which case the empty document the picker created is deleted again.
     */
    fun save(
        id: String,
        dest: Uri,
        quality: ExportQuality = ExportQuality.DEFAULT,
        size: PdfPageSize = PdfPageSize.DEFAULT,
        pageIds: Set<String>? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): SavedPdf? {
        val resolver = context.contentResolver
        val pages = exporter.writePdf(id, pageIds, quality, size, onProgress) { pdf ->
            // "wt" truncates, in case the picker handed back an existing file; not every provider supports it, so fall back to "w".
            val out = (try {
                resolver.openOutputStream(dest, "wt")
            } catch (e: IllegalArgumentException) {
                null
            } catch (e: UnsupportedOperationException) {
                null
            } ?: resolver.openOutputStream(dest, "w")) ?: throw IOException("Cannot write to $dest")
            out.use { o -> pdf.inputStream().use { it.copyTo(o) } }
            true
        }
        if (pages == null) {
            try {
                if (DocumentsContract.isDocumentUri(context, dest)) DocumentsContract.deleteDocument(resolver, dest)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove the unfinished $dest", e)
            }
            return null
        }
        return SavedPdf(dest, pages).also(onSaved)
    }

    /**
     * Writes document [id] (only [pageIds] if given) to a copy for sharing and returns its content URI, readable by whichever app [shareIntent]
     * grants it to; null on failure. Copies more than a day old are removed first; newer ones stay, as a receiving app may still be reading them.
     */
    fun share(
        id: String,
        quality: ExportQuality = ExportQuality.DEFAULT,
        size: PdfPageSize = PdfPageSize.DEFAULT,
        pageIds: Set<String>? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Uri? {
        val document = library.document(id) ?: return null
        val shareDir = ScanFiles.shareDir(context)
        val cutoff = clock() - SHARE_LIFETIME_MILLIS
        shareDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        // A folder per share keeps the file name readable in the receiving app without two shares overwriting each other.
        val folder = ScanFiles.newFolder(shareDir, "pdf-") ?: run {
            Log.w(TAG, "No folder for the share copy in $shareDir")
            return null
        }
        val target = File(folder, fileName(document))
        val pages = exporter.writePdf(id, pageIds, quality, size, onProgress) { pdf -> pdf.renameTo(target) || pdf.copyTo(target).exists() }
        if (pages == null) {
            folder.deleteRecursively()
            return null
        }
        return ScanFiles.contentUri(context, target)
    }

    companion object {
        private const val TAG = "ScanExport"
        private const val SHARE_LIFETIME_MILLIS = 24L * 60 * 60 * 1000

        /** An ACTION_SEND intent for a PDF from [share], with read access granted to the receiving app; wrap it in a chooser to show it. */
        fun shareIntent(uri: Uri, subject: String): Intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            // The grant reaches the receiving app through ClipData; EXTRA_STREAM alone is not enough on every Android version.
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        /** Adds a saved scan to PaperKnife+'s History. History is Compose state, so the entry is added on the main thread. */
        fun addToHistory(context: Context, saved: SavedPdf) {
            val name = getUriDetails(context, saved.uri).name
            val label = if (saved.pages == 1) "1 page" else "${saved.pages} pages"
            Handler(Looper.getMainLooper()).post {
                SessionManager.addEntry(name, "Scan", label, Icons.Filled.DocumentScanner, saved.uri, saved.pages)
            }
        }
    }
}
