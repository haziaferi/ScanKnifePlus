package com.haziaferi.scanknifeplus.scan.export

import android.util.Log
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scan.capture.AndroidPageImages
import com.haziaferi.scanknifeplus.scan.capture.PageImages
import com.haziaferi.scanknifeplus.scan.library.ScanLibrary
import com.haziaferi.scanknifeplus.scan.library.ScanPage
import com.haziaferi.scanknifeplus.scanner.export.ExportQuality
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import java.io.File
import java.io.IOException

/**
 * Turns a scan library document into a PDF (OpenScan file_operations.dart saveToDevice, saveForSharing and _compressedForPdf). Every page is
 * first copied, or re-encoded for a smaller [ExportQuality], into a work folder of its own under [workRoot], so a page edited or deleted while
 * the PDF is written cannot pull a file out from under it; the folder is always deleted afterwards. Blocking: call off the main thread.
 */
class ScanPdfExporter(
    private val library: ScanLibrary,
    private val workRoot: File,
    private val images: PageImages = AndroidPageImages,
    private val assembler: PdfAssembler = PdfBoxAssembler,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Writes the pages [pageIds] of document [id] (all of them when null, in document order) as a PDF and hands the finished file to [deliver],
     * which must copy or move it before returning. Returns the number of pages written, or null if the document or every selected page is gone,
     * the PDF could not be written, or [deliver] returned false or threw; never throws. [onProgress] is called with (pages ready, total) as pages are prepared.
     */
    fun writePdf(
        id: String,
        pageIds: Set<String>?,
        quality: ExportQuality,
        size: PdfPageSize,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        deliver: (File) -> Boolean,
    ): Int? {
        pruneStale()
        val work = ScanFiles.newFolder(workRoot, "pdf-") ?: run {
            Log.w(TAG, "No work folder for the export in $workRoot")
            return null
        }
        try {
            // A page file can disappear if an edit is committed while it is copied: try once more with the document as it is now.
            repeat(2) {
                val pages = selectedPages(id, pageIds) ?: return null
                val staged = stage(id, pages, quality, work, onProgress) ?: return@repeat
                val pdf = File(work, "export.pdf")
                if (!assembler.write(staged, size, work, pdf)) return null
                return if (deliver(pdf)) pages.size else null
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Export failed", e)
            return null
        } finally {
            work.deleteRecursively()
        }
    }

    private fun selectedPages(id: String, pageIds: Set<String>?): List<ScanPage>? {
        val pages = library.document(id)?.pages ?: return null
        val selected = if (pageIds == null) pages else pages.filter { it.id in pageIds }
        return selected.ifEmpty { null }
    }

    /**
     * The JPEG files the PDF is built from, numbered in page order in [work], or null if a page file could not be read. A preset smaller than
     * the stored pages re-encodes them; if any page fails that, every page goes in as stored, as in OpenScan: a full-size PDF beats no PDF.
     */
    private fun stage(id: String, pages: List<ScanPage>, quality: ExportQuality, work: File, onProgress: (Int, Int) -> Unit): List<File>? {
        work.listFiles()?.forEach { it.deleteRecursively() }
        if (!quality.usesStoredPages) {
            val encoded = mutableListOf<File>()
            for ((i, page) in pages.withIndex()) {
                val dest = File(work, "$i.jpg")
                if (!images.normalize(library.file(id, page.image), dest, quality.maxEdge, quality.jpegQuality)) break
                encoded += dest
                onProgress(i + 1, pages.size)
            }
            if (encoded.size == pages.size) return encoded
            Log.w(TAG, "Re-encoding failed; exporting the pages as stored")
            encoded.forEach { it.delete() }
        }
        val fallback = !quality.usesStoredPages
        return pages.mapIndexed { i, page ->
            val dest = File(work, "$i.jpg")
            try {
                library.file(id, page.image).inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            } catch (e: IOException) {
                Log.w(TAG, "Page ${page.id} could not be read", e)
                return null
            }
            // After a failed re-encode the count already moved on; copying is quick, so it is not walked back to 1.
            if (!fallback) onProgress(i + 1, pages.size)
            dest
        }
    }

    /** Removes work folders left by an export that was killed before it could clean up. */
    private fun pruneStale() {
        val cutoff = clock() - STALE_MILLIS
        workRoot.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val TAG = "ScanPdfExporter"
        const val STALE_MILLIS = 24L * 60 * 60 * 1000
    }
}
