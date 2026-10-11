package com.haziaferi.scanknifeplus.scan.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.haziaferi.scanknifeplus.scanner.cv.PerspectiveCrop
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.filter.Filter
import com.haziaferi.scanknifeplus.scanner.store.StoredImage
import java.io.File

/** The image work behind page edits; [AndroidPageImages] in the app, a fake in tests. Each returns false (writing nothing) if it fails. */
interface PageImages {
    /** Writes [source] with [filter] applied, at page quality (OpenScan apply_filter.dart filterEncodedImage). */
    fun filter(source: File, filter: Filter, dest: File): Boolean

    /**
     * Crops [quad] out of [source], turns the result clockwise by [quarterTurns], and writes it fitted to the page cap at page quality. [quad] is
     * in fractions of the source's upright width and height.
     */
    fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File): Boolean

    /** Writes [source] fitted to [maxEdge] at [quality] (OpenScan compress.dart normalizeImageIsolateEntry). */
    fun normalize(source: File, dest: File, maxEdge: Int, quality: Int): Boolean

    /** Writes [source] turned clockwise by [quarterTurns] (1 to 3), pixel for pixel, at page quality. */
    fun rotate(source: File, quarterTurns: Int, dest: File): Boolean
}

/**
 * Page edits with the platform codecs: pages are decoded whole (at most 3200 px) and resized with OpenScan's average [StoredImage.fitToMaxEdge].
 * Unlike OpenScan's crop screen, a crop stays in memory instead of a quality-100 JPEG round trip, which saves a generation of JPEG loss.
 * Blocking.
 */
object AndroidPageImages : PageImages {
    override fun filter(source: File, filter: Filter, dest: File): Boolean = attempt {
        val image = ImageCodec.decodeScaled(ImageSource.of(source), 0) ?: return@attempt false
        filter.apply(image.pixels, image.width, image.height)
        ImageCodec.writeJpeg(image, dest, StoredImage.PAGE_QUALITY)
    }

    override fun crop(source: File, quad: Quad, quarterTurns: Int, dest: File): Boolean = attempt {
        // Scoped so the decoded source can be collected before the resize allocates.
        val cropped = run {
            val image = ImageCodec.decodeScaled(ImageSource.of(source), 0) ?: return@attempt false
            PerspectiveCrop.cropToPage(image, quad.scaled(image.width.toDouble(), image.height.toDouble()), quarterTurns)
        } ?: return@attempt false
        ImageCodec.writeJpeg(StoredImage.fitToMaxEdge(cropped, StoredImage.PAGE_MAX_EDGE), dest, StoredImage.PAGE_QUALITY)
    }

    override fun normalize(source: File, dest: File, maxEdge: Int, quality: Int): Boolean = attempt {
        val image = ImageCodec.decodeScaled(ImageSource.of(source), 0) ?: return@attempt false
        StoredImage.flattenOntoWhite(image.pixels)
        ImageCodec.writeJpeg(StoredImage.fitToMaxEdge(image, maxEdge), dest, quality)
    }

    override fun rotate(source: File, quarterTurns: Int, dest: File): Boolean = attempt {
        val upright = ImageCodec.decodeUpright(ImageSource.of(source)) ?: return@attempt false
        var turned: Bitmap? = null
        try {
            // Unfiltered: a turn by quarters moves pixels without resampling them.
            val matrix = Matrix().apply { setRotate(90f * quarterTurns) }
            turned = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, matrix, false)
            ImageCodec.writeJpeg(turned, dest, StoredImage.PAGE_QUALITY)
        } finally {
            if (turned !== upright) turned?.recycle()
            upright.recycle()
        }
    }

    private inline fun attempt(block: () -> Boolean): Boolean = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "Page edit failed", e)
        false
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Page edit ran out of memory", e)
        false
    }

    private const val TAG = "PageImages"
}
