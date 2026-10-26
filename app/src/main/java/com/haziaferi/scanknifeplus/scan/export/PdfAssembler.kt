package com.haziaferi.scanknifeplus.scan.export

import android.util.Log
import com.haziaferi.scanknifeplus.ocr.OcrPage
import com.haziaferi.scanknifeplus.scanner.export.PdfLayout
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.ByteArrayOutputStream
import java.io.File

/** Writes JPEG pages into a PDF, one page each. */
fun interface PdfAssembler {
    /**
     * Writes [pages] to [dest] on [size] pages (for [PdfPageSize.FIT], each its image's shape), using [workDir] for scratch space, encrypted so
     * it opens only with [password] unless that is null; false if it could not. [text] runs parallel to [pages]: each page with a read page there
     * gets it as a searchable text layer, a page past its end or with null gets none. Blocking.
     */
    fun write(pages: List<File>, size: PdfPageSize, workDir: File, dest: File, password: String?, text: List<OcrPage?>): Boolean
}

/**
 * OpenScan's createPdf with pdfbox: each JPEG is embedded as it is and drawn where [PdfLayout] puts it, buffered in temp files under the work
 * folder so a long scan cannot run out of heap, with the [PdfTextLayer] over it where there is text; failures, including running out of memory
 * and a password SASLprep rejects, return false. Pages must be as the library stores them: upright without EXIF orientation, and 3-component
 * JPEGs, since pdfbox labels every JPEG DeviceRGB.
 */
object PdfBoxAssembler : PdfAssembler {
    override fun write(pages: List<File>, size: PdfPageSize, workDir: File, dest: File, password: String?, text: List<OcrPage?>): Boolean = try {
        PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(workDir)).use { doc ->
            // Loaded once, and only for a PDF that has text, so a PDF without any stays as it was.
            val font by lazy { PdfTextLayer.loadFont(doc) }
            for ((i, file) in pages.withIndex()) {
                val image = file.inputStream().use { JPEGFactory.createFromStream(doc, it) }
                val box = size.pageFor(image.width, image.height)
                val page = PDPage(PDRectangle(box.width.toFloat(), box.height.toFloat()))
                doc.addPage(page)
                val r = PdfLayout.imageRect(size, image.width, image.height)
                PDPageContentStream(doc, page).use { stream ->
                    stream.drawImage(image, r.x.toFloat(), r.y.toFloat(), r.width.toFloat(), r.height.toFloat())
                    text.getOrNull(i)?.takeIf { it.words.isNotEmpty() }?.let { PdfTextLayer.draw(stream, font, it, r) }
                }
            }
            if (password != null) protect(doc, password)
            doc.save(dest)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not write the PDF", e)
        false
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Ran out of memory writing the PDF", e)
        false
    }

    /** Whether [password] can encrypt a PDF, which SASLprep decides only when the PDF is saved; tried on an empty one-page PDF in memory. */
    fun accepts(password: String): Boolean = try {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            protect(doc, password)
            doc.save(ByteArrayOutputStream())
        }
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    // Protect's settings (protectPdf in PdfPages.kt): AES-256, the password as both user and owner password, all permissions.
    private fun protect(doc: PDDocument, password: String) =
        doc.protect(StandardProtectionPolicy(password, password, AccessPermission()).apply { encryptionKeyLength = 256 })

    private const val TAG = "PdfAssembler"
}
