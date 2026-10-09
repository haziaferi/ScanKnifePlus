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
 *  - the original, when asked for, is a separate decode fitted to [StoredImage.ORIGINAL_MAX_EDGE] and written at quality 80, after the page's
 *    buffers are released. One that fails is simply not recorded; it never costs the page.
 * Never throws. Differences from OpenScan: files are written atomically, and there is no second decoder to fall back on. OpenScan retries with
 * the pure-Dart `image` package, which also reads a few rare formats Android cannot (TIFF, TGA, PSD); here those, like bytes nothing can decode,
 * store nothing. Blocking: call off the main thread.
 */
object CaptureStore {
    /** Stores [source]; [quad] is in fractional portrait coordinates, or null to keep the whole image. Returns null if no page could be written. */
    fun store(source: ImageSource, quad: Quad?, pageDest: File, originalDest: File?): StoredCapture? {
        val header = ImageCodec.readHeader(source) ?: return null
        val page = storePage(source, header, quad, pageDest) ?: return null
        val original = originalDest?.let { storeOriginal(source, header, it) }
        return page.copy(original = original)
    }

    private fun storePage(source: ImageSource, header: ImageHeader, quad: Quad?, dest: File): StoredCapture? = try {
        val size = header.oriented
        val decoded = ImageCodec.decodeScaled(source, StoredImage.pageDecodeMaxEdge(size.width, size.height, quad), header)
        if (decoded == null) {
            null
        } else {
            StoredImage.flattenOntoWhite(decoded.pixels)
            // A warp that fails keeps the uncropped decode, as OpenScan does; running out of memory is not a warp failure and ends the page.
            val warped = quad?.let {
                try {
                    PerspectiveCrop.warpToPage(decoded, PerspectiveCrop.quadInPixelsOf(it, decoded.width, decoded.height), StoredImage.PAGE_MAX_EDGE)
                } catch (e: Exception) {
                    null
                }
            }
            val page = warped ?: decoded
            if (ImageCodec.writeJpeg(page, dest, StoredImage.PAGE_QUALITY)) StoredCapture(dest, null, warped != null, page.width, page.height) else null
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun storeOriginal(source: ImageSource, header: ImageHeader, dest: File): File? = try {
        val image = ImageCodec.decodeScaled(source, StoredImage.ORIGINAL_MAX_EDGE, header)
        if (image == null) {
            null
        } else {
            StoredImage.flattenOntoWhite(image.pixels)
            if (ImageCodec.writeJpeg(image, dest, StoredImage.ORIGINAL_QUALITY)) dest else null
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
