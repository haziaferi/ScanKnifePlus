package com.haziaferi.scanknifeplus.scan.capture

import com.haziaferi.scanknifeplus.scanner.cv.PerspectiveCrop
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.store.StoredImage
import java.io.File

/** A capture as stored: the page (cropped to the quad when one was given and the warp worked) and, if kept, the uncropped original. */
data class StoredCapture(val page: File, val original: File?, val cropped: Boolean, val pageWidth: Int, val pageHeight: Int)

/**
 * Turns a camera capture or a picked image into stored files, following OpenScan's native capture path (file_operations.dart _writeCaptureNatively
 * and capture_pipeline.dart encodeStoredPageIsolateEntry):
 *  - the page is decoded at the smallest size that still fills a stored page ([StoredImage.pageDecodeMaxEdge]), flattened onto white, warped to
 *    the quad capped at [StoredImage.PAGE_MAX_EDGE] (falling back to the unwarped decode if the warp fails), and written at quality 85;
 *  - the original, when asked for, is a separate decode fitted to [StoredImage.ORIGINAL_MAX_EDGE] and written at quality 80. One that fails is
 *    simply not recorded; it never costs the page.
 * One decode per file keeps a single page-sized buffer alive at a time. Unlike OpenScan, bytes the platform cannot decode are not copied through:
 * OpenScan deletes such a copy anyway once its platform decoder fails to display it, so the outcome (no page) is the same. Blocking: call off the
 * main thread.
 */
object CaptureStore {
    /** Stores [source]; [quad] is in fractional portrait coordinates, or null to keep the whole image. Returns null if no page could be written. */
    fun store(source: ImageSource, quad: Quad?, pageDest: File, originalDest: File?): StoredCapture? {
        val size = ImageCodec.orientedSize(source) ?: return null
        val decoded = ImageCodec.decodeScaled(source, StoredImage.pageDecodeMaxEdge(size.width, size.height, quad)) ?: return null
        StoredImage.flattenOntoWhite(decoded.pixels)

        val warped = quad?.let {
            runCatching { PerspectiveCrop.warpToPage(decoded, PerspectiveCrop.quadInPixelsOf(it, decoded.width, decoded.height), StoredImage.PAGE_MAX_EDGE) }
                .getOrNull()
        }
        val page = warped ?: decoded
        if (!ImageCodec.writeJpeg(page, pageDest, StoredImage.PAGE_QUALITY)) return null

        val original = originalDest?.takeIf { dest ->
            val image = ImageCodec.decodeScaled(source, StoredImage.ORIGINAL_MAX_EDGE)
            image != null && run {
                StoredImage.flattenOntoWhite(image.pixels)
                ImageCodec.writeJpeg(image, dest, StoredImage.ORIGINAL_QUALITY)
            }
        }
        return StoredCapture(pageDest, original, cropped = warped != null, pageWidth = page.width, pageHeight = page.height)
    }
}
