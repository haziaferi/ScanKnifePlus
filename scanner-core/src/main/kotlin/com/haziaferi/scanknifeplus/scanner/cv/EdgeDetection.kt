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

    /** A 3x3 Gaussian blur (approximating OpenCV's `GaussianBlur(3x3)`). */
    fun gaussianBlur3(gray: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var k = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val sx = (x + dx).coerceIn(0, width - 1)
                        val sy = (y + dy).coerceIn(0, height - 1)
                        sum += gray.u8(sy * width + sx) * BLUR_KERNEL[k++]
                    }
                }
                out.setU8(y * width + x, dartRound(sum.toDouble() / BLUR_KERNEL_SUM).coerceIn(0, 255))
            }
        }
        return out
    }

    /**
     * Sobel gradient magnitude, clamped to 0-255. Stands in for OpenCV's `Canny` step: rather than reproducing full non-max-suppression plus hysteresis,
     * the magnitude image is thresholded (see [otsuThreshold]) and then dilated/closed, which is sufficient to find the outer boundary of a photographed document.
     */
    fun sobelMagnitude(gray: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)

        fun at(x: Int, y: Int): Int {
            val cx = x.coerceIn(0, width - 1)
            val cy = y.coerceIn(0, height - 1)
            return gray.u8(cy * width + cx)
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val gx = -at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1) +
                    at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1)
                val gy = -at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1) +
                    at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1)
                val mag = sqrt((gx * gx + gy * gy).toDouble())
                out.setU8(y * width + x, dartRound(mag.dartClamp(0.0, 255.0)))
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
     * Binary dilation with a roughly `(2*radius+1)` square structuring element, implemented as two separable max-filter passes
     * (O(n*radius) instead of O(n*radius^2)); stands in for OpenCV's `dilate(9x9)`.
     */
    fun dilate(mask: ByteArray, width: Int, height: Int, radius: Int): ByteArray {
        val rowPass = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var v: Byte = 0
                var dx = -radius
                while (dx <= radius && v.toInt() == 0) {
                    val sx = x + dx
                    if (sx in 0 until width && mask[y * width + sx].toInt() == 1) v = 1
                    dx++
                }
                rowPass[y * width + x] = v
            }
        }

        val out = ByteArray(width * height)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var v: Byte = 0
                var dy = -radius
                while (dy <= radius && v.toInt() == 0) {
                    val sy = y + dy
                    if (sy in 0 until height && rowPass[sy * width + x].toInt() == 1) v = 1
                    dy++
                }
                out[y * width + x] = v
            }
        }
        return out
    }

    private val BLUR_KERNEL = intArrayOf(1, 2, 1, 2, 4, 2, 1, 2, 1)
    private const val BLUR_KERNEL_SUM = 16
}
