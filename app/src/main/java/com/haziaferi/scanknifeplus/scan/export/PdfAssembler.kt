package com.haziaferi.scanknifeplus.scan.export

import android.util.Log
import com.haziaferi.scanknifeplus.scanner.export.PdfLayout
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File

/** Writes JPEG pages into a PDF, one page each. */
fun interface PdfAssembler {
    /** Writes [pages] to [dest] on [size] pages, using [workDir] for scratch space; false if it could not. Blocking. */
    fun write(pages: List<File>, size: PdfPageSize, workDir: File, dest: File): Boolean
}

/**
 * OpenScan's createPdf with pdfbox: each JPEG is embedded as it is and drawn where [PdfLayout] puts it, buffered in temp files under the work
 * folder so a long scan cannot run out of heap; any failure returns false. Pages must be as the library stores them: upright without EXIF
 * orientation, and 3-component JPEGs, since pdfbox labels every JPEG DeviceRGB.
 */
object PdfBoxAssembler : PdfAssembler {
    override fun write(pages: List<File>, size: PdfPageSize, workDir: File, dest: File): Boolean = try {
        PDDocument(MemoryUsageSetting.setupTempFileOnly().setTempDir(workDir)).use { doc ->
            for (file in pages) {
                val image = file.inputStream().use { JPEGFactory.createFromStream(doc, it) }
                val page = PDPage(PDRectangle(size.width.toFloat(), size.height.toFloat()))
                doc.addPage(page)
                val r = PdfLayout.imageRect(size, image.width, image.height)
                PDPageContentStream(doc, page).use { it.drawImage(image, r.x.toFloat(), r.y.toFloat(), r.width.toFloat(), r.height.toFloat()) }
            }
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

    private const val TAG = "PdfAssembler"
}
