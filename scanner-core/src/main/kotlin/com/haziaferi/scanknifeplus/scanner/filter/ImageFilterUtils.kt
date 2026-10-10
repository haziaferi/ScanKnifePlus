// Ported from OpenScan lib/core/image_filter/utils/image_filter_utils.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.filter

import com.haziaferi.scanknifeplus.scanner.dartRound
import com.haziaferi.scanknifeplus.scanner.setU8
import com.haziaferi.scanknifeplus.scanner.u8

/**
 * Per-pixel primitives shared by the document filters. Each rewrites an RGBA buffer (stride 4) in place and leaves alpha untouched.
 */
object ImageFilterUtils {
    fun clampPixel(x: Int): Int = x.coerceIn(0, 255)

    /** Pushes colours away from (positive) or towards (negative) grey. */
    fun saturation(bytes: ByteArray, saturation: Double) {
        val s = if (saturation < -1) -1.0 else saturation
        var i = 0
        while (i < bytes.size) {
            val r = bytes.u8(i)
            val g = bytes.u8(i + 1)
            val b = bytes.u8(i + 2)
            val gray = 0.2989 * r + 0.5870 * g + 0.1140 * b // weights from CCIR 601 spec
            bytes.setU8(i, clampPixel(dartRound(-gray * s + r * (1 + s))))
            bytes.setU8(i + 1, clampPixel(dartRound(-gray * s + g * (1 + s))))
            bytes.setU8(i + 2, clampPixel(dartRound(-gray * s + b * (1 + s))))
            i += 4
        }
    }

    /** Replaces every pixel with its luminance, written to all three channels so the buffer stays RGBA. */
    fun grayscale(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            val avg = clampPixel(dartRound(0.2126 * bytes.u8(i) + 0.7152 * bytes.u8(i + 1) + 0.0722 * bytes.u8(i + 2)))
            bytes.setU8(i, avg)
            bytes.setU8(i + 1, avg)
            bytes.setU8(i + 2, avg)
            i += 4
        }
    }

    /** Contrast around mid-grey; [adj] runs -1 (flat) to 1 (harsh). */
    fun contrast(bytes: ByteArray, adj: Double) {
        // OpenScan computes the factor once (a non-finite one does not throw) and throws only at the first pixel's round(), so an empty buffer
        // must pass untouched.
        if (bytes.isEmpty()) return
        DocumentFilterUtils.applyLutToRgb(bytes, contrastLut(adj))
    }

    /**
     * [contrast] as a lookup table: OpenScan's per-byte formula evaluated once per value. A finite factor stays below about 1e16, so no entry
     * overflows, and a non-finite one throws here as it would on the first pixel.
     */
    internal fun contrastLut(adj: Double): ByteArray {
        val a = adj * 255
        val factor = (259 * (a + 255)) / (255 * (259 - a))
        val lut = ByteArray(256)
        for (v in 0 until 256) {
            lut.setU8(v, clampPixel(dartRound(factor * (v - 128) + 128)))
        }
        return lut
    }
}
