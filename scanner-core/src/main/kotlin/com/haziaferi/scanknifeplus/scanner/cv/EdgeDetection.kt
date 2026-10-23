// Ported from OpenScan lib/core/cv/edge_detection.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.dartClamp
import com.haziaferi.scanknifeplus.scanner.dartRound
import com.haziaferi.scanknifeplus.scanner.setU8
import com.haziaferi.scanknifeplus.scanner.u8
import kotlin.math.sqrt

/**
 * Grayscale/blur/edge/dilate/threshold steps standing in for OpenCV's `cvtColor`/`GaussianBlur`/`Canny`/`dilate`/`threshold`.
 *
 * All functions operate on flat byte buffers (RGBA with stride 4, or single-channel) holding unsigned 0..255 values.
 */
object EdgeDetection {
    /** Converts an RGBA buffer (stride 4) to a single-channel luminance buffer. */
    fun rgbaToGrayscale(rgba: ByteArray, width: Int, height: Int): ByteArray {
        val gray = ByteArray(width * height)
        var i = 0
        for (p in gray.indices) {
            val r = rgba.u8(i)
            val g = rgba.u8(i + 1)
            val b = rgba.u8(i + 2)
            gray.setU8(p, dartRound(0.2126 * r + 0.7152 * g + 0.0722 * b).coerceIn(0, 255))
            i += 4
        }
        return gray
    }

    /** A 3x3 Gaussian blur (approximating OpenCV's `GaussianBlur(3x3)`), edges replicated. */
    fun gaussianBlur3(gray: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val up = (y - 1).coerceAtLeast(0) * width
            val mid = y * width
            val down = (y + 1).coerceAtMost(height - 1) * width
            for (x in 0 until width) {
                val l = if (x > 0) x - 1 else 0
                val r = if (x < width - 1) x + 1 else width - 1
                val sum = gray.u8(up + l) + 2 * gray.u8(up + x) + gray.u8(up + r) +
                    2 * gray.u8(mid + l) + 4 * gray.u8(mid + x) + 2 * gray.u8(mid + r) +
                    gray.u8(down + l) + 2 * gray.u8(down + x) + gray.u8(down + r)
                // Dart's round(sum / 16) on a non-negative sum: half rounds up.
                out.setU8(mid + x, (sum + BLUR_KERNEL_SUM / 2) / BLUR_KERNEL_SUM)
            }
        }
        return out
    }

    /**
     * Sobel gradient magnitude, clamped to 0-255. Stands in for OpenCV's `Canny`: instead of non-max suppression and hysteresis, the magnitude is
     * thresholded ([otsuThreshold]) and dilated, which is enough to find the outer boundary of a photographed document.
     */
    fun sobelMagnitude(gray: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        val roundedRoot = ROUNDED_ROOT
        for (y in 0 until height) {
            val up = (y - 1).coerceAtLeast(0) * width
            val mid = y * width
            val down = (y + 1).coerceAtMost(height - 1) * width
            for (x in 0 until width) {
                val l = if (x > 0) x - 1 else 0
                val r = if (x < width - 1) x + 1 else width - 1
                val gx = -gray.u8(up + l) - 2 * gray.u8(mid + l) - gray.u8(down + l) +
                    gray.u8(up + r) + 2 * gray.u8(mid + r) + gray.u8(down + r)
                val gy = -gray.u8(up + l) - 2 * gray.u8(up + x) - gray.u8(up + r) +
                    gray.u8(down + l) + 2 * gray.u8(down + x) + gray.u8(down + r)
                val s = gx * gx + gy * gy
                out.setU8(mid + x, if (s < roundedRoot.size) roundedRoot[s] else 255)
            }
        }
        return out
    }

    /** Otsu's method: picks a global threshold that best separates a bimodal histogram, standing in for OpenCV's `THRESH_TRIANGLE`. */
    fun otsuThreshold(image: ByteArray): Int {
        val hist = IntArray(256)
        for (i in image.indices) {
            hist[image.u8(i)]++
        }

        val total = image.size
        var sum = 0.0
        for (i in 0 until 256) {
            sum += (i.toLong() * hist[i]).toDouble()
        }

        var sumB = 0.0
        var wB = 0
        var maxVariance = -1.0
        var threshold = 128

        for (t in 0 until 256) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break

            sumB += (t.toLong() * hist[t]).toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            // Dart ints are 64-bit: wB * wF overflows a 32-bit Int on images over ~92k pixels.
            val between = (wB.toLong() * wF).toDouble() * (mB - mF) * (mB - mF)
            if (between > maxVariance) {
                maxVariance = between
                threshold = t
            }
        }
        return threshold
    }

    /** Binarizes [image] against threshold [t]: returns a 0/1 mask. */
    fun threshold(image: ByteArray, t: Int): ByteArray {
        val out = ByteArray(image.size)
        for (i in image.indices) {
            out[i] = if (image.u8(i) >= t) 1 else 0
        }
        return out
    }

    /**
     * Binary dilation with a `(2*radius+1)` square structuring element as two separable passes, each a sliding count of the set pixels in the
     * window; stands in for OpenCV's `dilate(9x9)`. Pixels outside the mask count as unset.
     */
    fun dilate(mask: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        val rowPass = ByteArray(width * height)
        for (y in 0 until height) {
            val base = y * width
            var count = 0
            for (x in 0..minOf(radius, width - 1)) count += mask.isSet(base + x)
            for (x in 0 until width) {
                if (count > 0) rowPass[base + x] = 1
                if (x - radius >= 0) count -= mask.isSet(base + x - radius)
                if (x + radius + 1 < width) count += mask.isSet(base + x + radius + 1)
            }
        }

        val out = ByteArray(width * height)
        for (x in 0 until width) {
            var count = 0
            for (y in 0..minOf(radius, height - 1)) count += rowPass[y * width + x].toInt()
            for (y in 0 until height) {
                if (count > 0) out[y * width + x] = 1
                if (y - radius >= 0) count -= rowPass[(y - radius) * width + x].toInt()
                if (y + radius + 1 < height) count += rowPass[(y + radius + 1) * width + x].toInt()
            }
        }
        return out
    }

    private const val BLUR_KERNEL_SUM = 16

    /** 1 if the mask pixel is exactly 1 (set), else 0, as the original dilation tests it. */
    private fun ByteArray.isSet(i: Int): Int = if (this[i].toInt() == 1) 1 else 0

    /** `round(min(sqrt(s), 255))` for every squared gradient magnitude `s` up to 255 squared; anything larger rounds to 255. */
    private val ROUNDED_ROOT = IntArray(255 * 255 + 1) { dartRound(sqrt(it.toDouble()).dartClamp(0.0, 255.0)) }
}
