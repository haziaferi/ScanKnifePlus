package com.haziaferi.scanknifeplus.scan.capture

import com.haziaferi.scanknifeplus.scanner.cv.BookPage
import com.haziaferi.scanknifeplus.scanner.cv.BookSplit
import com.haziaferi.scanknifeplus.scanner.cv.PerspectiveCrop
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.store.StoredImage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.roundToLong

/** A capture as stored: the page (cropped to the quad when one was given and the warp worked) and, if kept, the uncropped original. */
data class StoredCapture(val page: File, val original: File?, val cropped: Boolean, val pageWidth: Int, val pageHeight: Int)

/** A book spread as stored: its two [pages], left first, and the [spread] they were split from, in fractions of the photo's upright size. */
data class StoredBook(val pages: List<StoredCapture>, val spread: Quad)

/**
 * Turns a camera capture or a picked image into stored files, following OpenScan's native capture path (file_operations.dart
 * _writeCaptureNatively, capture_pipeline.dart encodeStoredPageIsolateEntry): the page decoded just large enough, flattened onto white, warped
 * to the quad (the unwarped decode if the warp fails) and written at quality 85, then the original, if asked for, as a separate decode at
 * quality 80 that never costs the page. Never throws, blocking; unlike OpenScan, files are written atomically and there is no pure-Dart
 * fallback decoder, so the rare formats only it reads (TIFF, TGA, PSD) store nothing.
 */
object CaptureStore {
    /** Stores [source]; [quad] is in fractional portrait coordinates, or null to keep the whole image. Returns null if no page could be written. */
    fun store(source: ImageSource, quad: Quad?, pageDest: File, originalDest: File?): StoredCapture? {
        val header = ImageCodec.readHeader(source) ?: return null
        val page = storePage(source, header, quad, pageDest) ?: return null
        val original = originalDest?.let { storeOriginal(source, header, it) }
        return page.copy(original = original)
    }

    /**
     * Stores [source], a photo of an open book, as its two facing pages, left first: [spread] (fractional portrait coordinates, or null for the
     * whole image) is split as [BookSplit.page] splits it, and each page is warped, turned upright by [quarterTurns] and written to [pageDests].
     * With [originalDests] the original is encoded once into the first file and copied byte for byte into the second, so each page owns an
     * identical file: if the encode fails neither page gets one, and if only the copy fails the right page goes without. Returns the pages with
     * the spread in the original's coordinates, or null, leaving nothing in [pageDests], if either page could not be written.
     */
    fun storeBook(
        source: ImageSource,
        spread: Quad?,
        split: Double,
        quarterTurns: Int,
        pageDests: Pair<File, File>,
        originalDests: Pair<File, File>?,
    ): StoredBook? {
        val dests = pageDests.toList()
        val header = ImageCodec.readHeader(source)
        val pages = header?.let { storeBookPages(source, it, spread, split, quarterTurns, dests) }
        if (header == null || pages == null) {
            dests.forEach { it.delete() }
            return null
        }
        val size = header.oriented
        val stored = StoredBook(pages, spreadIn(spread, size.width, size.height).scaled(1.0 / size.width, 1.0 / size.height))
        if (originalDests == null) return stored
        val first = storeOriginal(source, header, originalDests.first)
        val originals = listOf(first, first?.let { copied(it, originalDests.second) })
        return stored.copy(pages = pages.zip(originals) { page, original -> page.copy(original = original) })
    }

    private fun storeBookPages(
        source: ImageSource,
        header: ImageHeader,
        spread: Quad?,
        split: Double,
        quarterTurns: Int,
        dests: List<File>,
    ): List<StoredCapture>? {
        try {
            val size = header.oriented
            val whole = spreadIn(spread, size.width, size.height)
            // As StoredImage.pageDecodeMaxEdge, but for the larger page rather than the whole spread, so each page can reach the page cap.
            val longestOut = BookPage.entries.maxOf { page ->
                PerspectiveCrop.outputSize(BookSplit.page(whole, page, split, quarterTurns)).let { maxOf(it.width, it.height) }
            }
            val longestSrc = maxOf(size.width, size.height)
            val edge = if (longestOut <= StoredImage.PAGE_MAX_EDGE) {
                longestSrc
            } else {
                maxOf(1L, (longestSrc.toLong() * StoredImage.PAGE_MAX_EDGE / longestOut.toDouble()).roundToLong()).toInt()
            }
            val decoded = ImageCodec.decodeScaled(source, edge, header) ?: return null
            StoredImage.flattenOntoWhite(decoded.pixels)
            val pixels = spreadIn(spread, decoded.width, decoded.height)
            return BookPage.entries.zip(dests) { page, dest ->
                val image = BookSplit.cropPage(decoded, pixels, page, split, quarterTurns, StoredImage.PAGE_MAX_EDGE) ?: return null
                if (!ImageCodec.writeJpeg(image, dest, StoredImage.PAGE_QUALITY)) return null
                StoredCapture(dest, null, true, image.width, image.height)
            }
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
    }

    /** [spread] on a [width] x [height] image's pixel grid; the whole image when null. */
    private fun spreadIn(spread: Quad?, width: Int, height: Int): Quad = spread?.let { PerspectiveCrop.quadInPixelsOf(it, width, height) }
        ?: Quad(Pt(0.0, 0.0), Pt(width.toDouble(), 0.0), Pt(width.toDouble(), height.toDouble()), Pt(0.0, height.toDouble()))

    /** Copies [from] to [to] through a temp file beside it, as [ImageCodec.writeJpeg] writes; [to], or null (leaving no temp file) on failure. */
    private fun copied(from: File, to: File): File? {
        val tmp = File(to.path + ".tmp")
        return try {
            from.inputStream().use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            if (tmp.renameTo(to)) to else null
        } catch (e: IOException) {
            null
        } finally {
            tmp.delete()
        }
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
