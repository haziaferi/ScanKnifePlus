// Ported from OpenScan lib/core/cv/compress.dart (store constants), native_decode.dart (_fitted, flattenOntoWhite) and
// lib/core/data/file_operations.dart (_decodeForPage). Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.store

import com.haziaferi.scanknifeplus.scanner.cv.PageSize
import com.haziaferi.scanknifeplus.scanner.cv.PerspectiveCrop
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.cv.RgbaImage
import com.haziaferi.scanknifeplus.scanner.dartRound

/** How captured pages and kept originals are sized on their way into storage. Pure arithmetic; decoding and encoding happen in the app. */
object StoredImage {
    /** Long edge a stored page is capped at: about 200 DPI across an A4 page, past which a photo of paper carries no more readable detail. */
    const val PAGE_MAX_EDGE = 2400
    const val PAGE_QUALITY = 85

    /** Kept originals get a looser cap, since they exist to be re-cropped down. */
    const val ORIGINAL_MAX_EDGE = 3200
    const val ORIGINAL_QUALITY = 80

    /** [width] x [height] scaled down to fit [maxEdge] on its long side, or unchanged if it already fits (or [maxEdge] <= 0); never upscaled. */
    fun fitted(width: Int, height: Int, maxEdge: Int): PageSize {
        val longest = maxOf(width, height)
        if (maxEdge <= 0 || longest <= maxEdge) return PageSize(width, height)
        val scale = maxEdge.toDouble() / longest
        return PageSize(maxOf(1, dartRound(width * scale)), maxOf(1, dartRound(height * scale)))
    }

    /**
     * OpenScan's fitToMaxEdge (compress.dart): [image] box-averaged down so its long edge is [maxEdge], or [image] itself if it already fits or
     * [maxEdge] is null. Used where OpenScan normalizes an already-decoded page, such as a re-crop. As in the `image` package's copyResize, the
     * long side is set to [maxEdge] (width when the image is square) and the other is `round(maxEdge * (other / long))`.
     */
    fun fitToMaxEdge(image: RgbaImage, maxEdge: Int?): RgbaImage {
        if (maxEdge == null || maxOf(image.width, image.height) <= maxEdge) return image
        return if (image.width >= image.height) {
            PerspectiveCrop.averageResize(image, maxEdge, dartRound(maxEdge * (image.height.toDouble() / image.width)))
        } else {
            PerspectiveCrop.averageResize(image, dartRound(maxEdge * (image.width.toDouble() / image.height)), maxEdge)
        }
    }

    /**
     * The long edge to decode a [width] x [height] capture at so its stored page comes out at the page cap. Without a [quad] that is simply the
     * cap. With one, the warp only reads the quad's region, so the decode is scaled for the quad's own natural size to land on the cap and the
     * warp then runs about 1:1; a quad that is already within the cap decodes the capture whole. [quad] is in fractional portrait coordinates.
     */
    fun pageDecodeMaxEdge(width: Int, height: Int, quad: Quad?): Int {
        if (quad == null) return PAGE_MAX_EDGE
        val natural = PerspectiveCrop.outputSize(PerspectiveCrop.quadInPixelsOf(quad, width, height))
        val longestOut = maxOf(natural.width, natural.height)
        val longestSrc = maxOf(width, height)
        if (longestOut <= PAGE_MAX_EDGE) return longestSrc
        return maxOf(1, dartRound(longestSrc.toLong() * PAGE_MAX_EDGE / longestOut.toDouble()))
    }

    /**
     * Flattens premultiplied RGBA onto white, in place, so transparency in a picked image becomes paper rather than black once the JPEG encoder
     * drops alpha. Premultiplied channels never exceed alpha, so compositing over white is `channel + (255 - alpha)`; opaque pixels are untouched.
     */
    fun flattenOntoWhite(rgba: ByteArray) {
        var i = 0
        while (i < rgba.size) {
            val alpha = rgba[i + 3].toInt() and 0xFF
            if (alpha != 255) {
                val white = 255 - alpha
                for (c in 0..2) rgba[i + c] = ((rgba[i + c].toInt() and 0xFF) + white).toByte()
                rgba[i + 3] = 255.toByte()
            }
            i += 4
        }
    }
}
