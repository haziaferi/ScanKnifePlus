// Ported from OpenScan lib/core/image_filter/utils/document_filter_utils.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.filter

import com.haziaferi.scanknifeplus.scanner.dartRound
import com.haziaferi.scanknifeplus.scanner.setU8
import com.haziaferi.scanknifeplus.scanner.u8
import kotlin.math.floor

/**
 * Global and neighbourhood primitives for the document filters: histograms, percentile bounds, lookup tables, box blur and box downscaling.
 * All functions are pure and operate on flat buffers.
 */
object DocumentFilterUtils {
    /**
     * Summed-area table of a single-channel buffer, `(width + 1) * (height + 1)` so row/column 0 is an all-zero border. Entries wrap at 2^32
     * like the original's `Uint32List`, which is wide enough for a fully white image of ~16.8 megapixels.
     */
    fun integralImage(gray: ByteArray, width: Int, height: Int): LongArray {
        val stride = width + 1
        val table = LongArray(stride * (height + 1))
        for (y in 0 until height) {
            var rowSum = 0L
            val srcRow = y * width
            val dstRow = (y + 1) * stride
            val aboveRow = y * stride
            for (x in 0 until width) {
                rowSum += gray.u8(srcRow + x)
                table[dstRow + x + 1] = (table[aboveRow + x + 1] + rowSum) and 0xFFFFFFFFL
            }
        }
        return table
    }

    /** Sum of the [integralImage] window with inclusive corners (x0, y0)-(x1, y1). Coordinates are clamped by the caller. */
    fun boxSum(table: LongArray, width: Int, x0: Int, y0: Int, x1: Int, y1: Int): Long {
        val stride = width + 1
        val top = y0 * stride
        val bottom = (y1 + 1) * stride
        return table[bottom + x1 + 1] - table[bottom + x0] - table[top + x1 + 1] + table[top + x0]
    }

    /**
     * Box blur of a single-channel buffer with a `(2 * radius + 1)` square window, in O(n) via [integralImage]. At a large radius the result is
     * the local paper/board brightness with the ink averaged away, which is what the B&W and Whiteboard filters divide against.
     */
    fun boxBlur(gray: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        val table = integralImage(gray, width, height)
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val y0 = if (y - radius < 0) 0 else y - radius
            val y1 = if (y + radius >= height) height - 1 else y + radius
            for (x in 0 until width) {
                val x0 = if (x - radius < 0) 0 else x - radius
                val x1 = if (x + radius >= width) width - 1 else x + radius
                val count = (x1 - x0 + 1) * (y1 - y0 + 1)
                out.setU8(y * width + x, (boxSum(table, width, x0, y0, x1, y1) / count).toInt())
            }
        }
        return out
    }

    /** 256-bin histogram of one channel of an RGBA buffer. */
    fun channelHistogram(rgba: ByteArray, channel: Int): IntArray {
        val histogram = IntArray(256)
        var i = channel
        while (i < rgba.size) {
            histogram[rgba.u8(i)]++
            i += 4
        }
        return histogram
    }

    /** 256-bin histogram of a single-channel buffer. */
    fun grayHistogram(gray: ByteArray): IntArray {
        val histogram = IntArray(256)
        for (i in gray.indices) {
            histogram[gray.u8(i)]++
        }
        return histogram
    }

    /**
     * The values below which [lowFraction] of the samples fall and above which [highFraction] of them fall, as `[low, high]`. Clipping a small fraction
     * off each end keeps a single dust speck or specular highlight from pinning the whole range (the standard "auto levels" trick).
     */
    fun percentileBounds(histogram: IntArray, lowFraction: Double, highFraction: Double): IntArray {
        var total = 0L
        for (count in histogram) {
            total += count
        }
        if (total == 0L) return intArrayOf(0, 255)

        val lowTarget = floor(total * lowFraction).toLong()
        val highTarget = floor(total * highFraction).toLong()

        var low = 0
        var high = 255
        var seen = 0L
        for (v in 0 until 256) {
            seen += histogram[v]
            if (seen > lowTarget) {
                low = v
                break
            }
        }
        seen = 0L
        for (v in 255 downTo 0) {
            seen += histogram[v]
            if (seen > highTarget) {
                high = v
                break
            }
        }
        if (high <= low) high = low + 1
        return intArrayOf(low, high)
    }

    /** A 256-entry lookup table that linearly stretches `[low, high]` onto the full `0..255` range. */
    fun stretchLut(low: Int, high: Int): ByteArray {
        val lut = ByteArray(256)
        val span = (high - low).toDouble()
        for (v in 0 until 256) {
            lut.setU8(v, ImageFilterUtils.clampPixel(dartRound(((v - low) / span) * 255)))
        }
        return lut
    }

    /** Applies [lut] to one channel of an RGBA buffer, in place. */
    fun applyLutToChannel(rgba: ByteArray, channel: Int, lut: ByteArray) {
        var i = channel
        while (i < rgba.size) {
            rgba[i] = lut[rgba.u8(i)]
            i += 4
        }
    }

    /** Applies [lut] to all three colour channels of an RGBA buffer, in place, leaving alpha untouched. */
    fun applyLutToRgb(rgba: ByteArray, lut: ByteArray) {
        var i = 0
        while (i < rgba.size) {
            rgba[i] = lut[rgba.u8(i)]
            rgba[i + 1] = lut[rgba.u8(i + 1)]
            rgba[i + 2] = lut[rgba.u8(i + 2)]
            i += 4
        }
    }

    /**
     * Box-averages a single-channel buffer down to [newWidth] x [newHeight]. The B&W and Whiteboard filters' local mean and illumination fields are
     * low-frequency by construction, so computing them on a downscaled copy costs nothing in quality.
     */
    fun downscaleGray(gray: ByteArray, width: Int, height: Int, newWidth: Int, newHeight: Int): ByteArray {
        val out = ByteArray(newWidth * newHeight)
        for (y in 0 until newHeight) {
            val y0 = y * height / newHeight
            var y1 = (y + 1) * height / newHeight
            if (y1 <= y0) y1 = y0 + 1
            for (x in 0 until newWidth) {
                val x0 = x * width / newWidth
                var x1 = (x + 1) * width / newWidth
                if (x1 <= x0) x1 = x0 + 1
                var sum = 0L
                var count = 0
                for (sy in y0 until y1) {
                    val row = sy * width
                    for (sx in x0 until x1) {
                        sum += gray.u8(row + sx)
                        count++
                    }
                }
                out.setU8(y * newWidth + x, (sum / count).toInt())
            }
        }
        return out
    }
}
