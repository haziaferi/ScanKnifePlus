// Ported from OpenScan lib/view/Widgets/view/export_bottomsheet.dart (quality presets, page sizes), lib/core/data/file_operations.dart
// (createPdf) and lib/core/data/document_naming.dart (exportFileName). Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.export

import com.haziaferi.scanknifeplus.scanner.store.StoredImage

/**
 * OpenScan's export presets: the JPEG quality every page is re-encoded at and the long edge it is capped at. Below about quality 45 JPEG artefacts
 * cost more legibility than the pixels are worth, so the small presets shed resolution instead. [HIGH] is what pages are stored at, so it exports
 * the stored files as they are.
 */
enum class ExportQuality(val jpegQuality: Int, val maxEdge: Int) {
    ULTRA_LOW(30, 900),
    LOW(45, 1200),
    MEDIUM(65, 1800),
    HIGH(StoredImage.PAGE_QUALITY, StoredImage.PAGE_MAX_EDGE);

    /** True when pages are already stored at this quality and size, so re-encoding them would only lose detail. */
    val usesStoredPages: Boolean get() = jpegQuality == StoredImage.PAGE_QUALITY && maxEdge == StoredImage.PAGE_MAX_EDGE

    companion object {
        /** The preset OpenScan's export sheet starts on. */
        val DEFAULT = MEDIUM
    }
}

/** PDF page sizes in points, computed as the Dart pdf package's PdfPageFormat does so the values match to the last bit. */
enum class PdfPageSize(val width: Double, val height: Double) {
    A4(21.0 * CM, 29.7 * CM),
    LETTER(8.5 * INCH, 11.0 * INCH),
    LEGAL(8.5 * INCH, 14.0 * INCH);

    companion object {
        val DEFAULT = A4
    }
}

private const val INCH = 72.0
private const val CM = INCH / 2.54

/** Where a page image is drawn on a PDF page, in points from the lower left corner. */
data class PdfImageRect(val x: Double, val y: Double, val width: Double, val height: Double)

object PdfLayout {
    /** OpenScan's page margin on every side. */
    const val MARGIN = 5.0

    /**
     * Where createPdf's `Center(Image(...))` puts a [imageWidth] x [imageHeight] image on a [page] with a [MARGIN] margin: scaled to fit the
     * printable area (up or down, keeping its aspect ratio) and centred in it.
     */
    fun imageRect(page: PdfPageSize, imageWidth: Int, imageHeight: Int): PdfImageRect {
        require(imageWidth > 0 && imageHeight > 0) { "Empty image: ${imageWidth}x$imageHeight" }
        val boxW = page.width - 2 * MARGIN
        val boxH = page.height - 2 * MARGIN
        val w = imageWidth.toDouble()
        val h = imageHeight.toDouble()
        // BoxFit.contain, as the pdf package's applyBoxFit computes it.
        val (drawW, drawH) = if (boxW / boxH > w / h) Pair(w * boxH / h, boxH) else Pair(boxW, h * boxW / w)
        return PdfImageRect(MARGIN + (boxW - drawW) / 2.0, MARGIN + (boxH - drawH) / 2.0, drawW, drawH)
    }
}

object ExportNames {
    /**
     * Longest name an export gets, before its extension. OpenScan has no limit, so a long document name gave a file name past the 255-byte limit
     * of Android file systems and the export failed.
     */
    const val MAX_LENGTH = 100

    private val NOT_ALLOWED = Regex("[^A-Za-z0-9 _-]")
    private val SPACES = Regex("\\s+")

    /**
     * OpenScan's exportFileName: [documentName] without anything a file system could object to (only ASCII letters, digits, space, `_` and `-`
     * are kept, and runs of spaces become `_`), cut to [MAX_LENGTH]. When nothing is left, OpenScan minted a new timestamp name; here the caller
     * passes [fallback], the document's own generated name, so the export still matches what the library shows.
     */
    fun exportFileName(documentName: String, fallback: String): String {
        val cleaned = NOT_ALLOWED.replace(documentName, "").trim().replace(SPACES, "_").take(MAX_LENGTH)
        return cleaned.ifEmpty { fallback }
    }
}
