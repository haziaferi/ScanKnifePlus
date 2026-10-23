// Ported from OSS-DocumentScanner (https://github.com/ossappscollective/OSS-DocumentScanner) at commit ff05fc52b601d1cccb4076f3f6c3318f74e72f0c:
// the edge masks of cpp/src/DocumentDetector.cpp scanPoint. MIT License, Copyright (c) 2022 Martin Guillon. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.setU8
import com.haziaferi.scanknifeplus.scanner.u8
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The image operations OSS-DocumentScanner's candidate generation calls from OpenCV, in pure Kotlin: area resampling, a black border, a 3x3
 * median blur, rectangular dilation and closing, and Canny. Single-channel buffers hold unsigned 0..255 values; masks hold 0/1.
 */
internal object EdgeMasks {
    /**
     * Area-average shrink of the channels at [offsets] (one or three) of [src] ([stride] bytes per pixel) to [dstW] x [dstH], like OpenCV's
     * `INTER_AREA`: each output pixel is the mean of the source area it covers, source pixels on a cell boundary weighted by their share. One
     * pass over the source for all channels; [dstW] and [dstH] must not exceed the source size.
     */
    fun areaResample(src: ByteArray, srcW: Int, srcH: Int, stride: Int, offsets: IntArray, dstW: Int, dstH: Int): List<ByteArray> {
        require(offsets.size == 1 || offsets.size == 3) { "one or three channels" }
        val xs = Split(srcW, dstW)
        val ys = Split(srcH, dstH)
        val channels = offsets.size
        val o0 = offsets[0]
        val o1 = offsets[if (channels == 3) 1 else 0]
        val o2 = offsets[if (channels == 3) 2 else 0]
        val first = xs.first
        val firstWeight = xs.firstWeight
        val row = DoubleArray(dstW * channels)
        val acc = DoubleArray(dstW * dstH * channels)
        for (y in 0 until srcH) {
            // Each output cell sums its source pixels left to right in local accumulators: the share of a boundary pixel that belongs to the
            // next cell is carried over and starts that cell's sum, the same additions in the same order as summing into the row directly.
            var p = y * srcW * stride
            var x = 0
            var carry0 = 0.0
            var carry1 = 0.0
            var carry2 = 0.0
            for (d in 0 until dstW) {
                var a0 = carry0
                var a1 = carry1
                var a2 = carry2
                carry0 = 0.0
                carry1 = 0.0
                carry2 = 0.0
                while (x < srcW && first[x] == d) {
                    val w0 = firstWeight[x]
                    val v0 = (src[p + o0].toInt() and 0xFF).toDouble()
                    val v1 = (src[p + o1].toInt() and 0xFF).toDouble()
                    val v2 = (src[p + o2].toInt() and 0xFF).toDouble()
                    if (w0 == 1.0) {
                        // v * 1.0 is v exactly, so whole pixels skip the multiply without changing a bit of the sum.
                        a0 += v0
                        a1 += v1
                        a2 += v2
                    } else {
                        a0 += v0 * w0
                        a1 += v1 * w0
                        a2 += v2 * w0
                        val w1 = 1 - w0
                        if (w1 > 0) {
                            carry0 = v0 * w1
                            carry1 = v1 * w1
                            carry2 = v2 * w1
                        }
                    }
                    p += stride
                    x++
                }
                row[d * channels] = a0
                if (channels == 3) {
                    row[d * 3 + 1] = a1
                    row[d * 3 + 2] = a2
                }
            }
            val wy0 = ys.firstWeight[y]
            val base0 = ys.first[y] * dstW * channels
            for (i in row.indices) acc[base0 + i] += row[i] * wy0
            if (wy0 < 1) {
                val base1 = base0 + dstW * channels
                for (i in row.indices) acc[base1 + i] += row[i] * (1 - wy0)
            }
        }
        val norm = (dstW.toDouble() / srcW) * (dstH.toDouble() / srcH)
        return List(channels) { c ->
            ByteArray(dstW * dstH) { i -> (acc[i * channels + c] * norm).roundToInt().coerceIn(0, 255).toByte() }
        }
    }

    /** Which output cell each source index falls in ([first]) and its share in that cell; the rest, if any, goes to the next cell. */
    private class Split(srcSize: Int, dstSize: Int) {
        val first = IntArray(srcSize)
        val firstWeight = DoubleArray(srcSize)

        init {
            val scale = srcSize.toDouble() / dstSize
            for (s in 0 until srcSize) {
                val d = min(dstSize - 1, floor(s / scale).toInt())
                val boundary = (d + 1) * scale
                first[s] = d
                firstWeight[s] = if (d < dstSize - 1 && boundary < s + 1) boundary - s else 1.0
            }
        }
    }

    /** Surrounds [src] with [border] black pixels on every side (OpenCV `copyMakeBorder` with a constant 0). */
    fun addBorder(src: ByteArray, width: Int, height: Int, border: Int): ByteArray {
        val w = width + 2 * border
        val out = ByteArray(w * (height + 2 * border))
        for (y in 0 until height) {
            System.arraycopy(src, y * width, out, (y + border) * w + border, width)
        }
        return out
    }

    /**
     * 3x3 median blur with replicated edges (OpenCV `medianBlur(src, 3)`). Each column's three values are sorted once per row; the median
     * of nine is then the median of the largest low, the median middle and the smallest high of the three columns.
     */
    fun medianBlur3(src: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height)
        val lo = IntArray(width)
        val mid = IntArray(width)
        val hi = IntArray(width)
        for (y in 0 until height) {
            val up = (y - 1).coerceAtLeast(0) * width
            val row = y * width
            val down = (y + 1).coerceAtMost(height - 1) * width
            for (x in 0 until width) {
                val a = src.u8(up + x)
                val b = src.u8(row + x)
                val c = src.u8(down + x)
                lo[x] = min(a, min(b, c))
                hi[x] = max(a, max(b, c))
                mid[x] = a + b + c - lo[x] - hi[x]
            }
            for (x in 0 until width) {
                val l = (x - 1).coerceAtLeast(0)
                val r = (x + 1).coerceAtMost(width - 1)
                val maxLo = max(lo[l], max(lo[x], lo[r]))
                val minHi = min(hi[l], min(hi[x], hi[r]))
                val medMid = median3(mid[l], mid[x], mid[r])
                out.setU8(row + x, median3(maxLo, medMid, minHi))
            }
        }
        return out
    }

    private fun median3(a: Int, b: Int, c: Int): Int = max(min(a, b), min(max(a, b), c))

    /** 1 where [src] is above [t] (OpenCV `THRESH_BINARY`), else 0. */
    fun thresholdAbove(src: ByteArray, t: Int): ByteArray {
        val out = ByteArray(src.size)
        for (i in src.indices) {
            if (src.u8(i) > t) out[i] = 1
        }
        return out
    }

    /**
     * Max ([dilate] true) or min filter of a 0/1 mask over a [size] x [size] rectangle anchored at its centre (`size / 2`, as OpenCV places
     * the anchor of an even kernel). Pixels outside the image are ignored, as OpenCV's default morphology border does.
     */
    fun rectFilter(mask: ByteArray, width: Int, height: Int, size: Int, dilate: Boolean): ByteArray {
        val lo = -(size / 2)
        val hi = size - 1 - size / 2
        val hit: Byte = if (dilate) 1 else 0
        val miss: Byte = if (dilate) 0 else 1
        // Separable: every hit spreads to the outputs whose window covers it, first along rows, then along columns.
        val rows = ByteArray(width * height)
        if (!dilate) rows.fill(1)
        for (y in 0 until height) {
            val base = y * width
            for (x in 0 until width) {
                if (mask[base + x] != hit) continue
                for (o in max(0, x - hi)..min(width - 1, x - lo)) rows[base + o] = hit
            }
        }
        val out = ByteArray(width * height)
        if (miss.toInt() == 1) out.fill(1)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (rows[y * width + x] != hit) continue
                for (o in max(0, y - hi)..min(height - 1, y - lo)) out[o * width + x] = hit
            }
        }
        return out
    }

    /** Morphological closing (dilate, then erode) with a [size] x [size] rectangle (OpenCV `morphologyEx(MORPH_CLOSE)`). */
    fun close(mask: ByteArray, width: Int, height: Int, size: Int): ByteArray =
        rectFilter(rectFilter(mask, width, height, size, dilate = true), width, height, size, dilate = false)

    /**
     * Canny edge detection as OpenCV's `Canny(src, low, high)` with its defaults (3x3 Sobel on replicated edges, L1 gradient magnitude):
     * non-maximum suppression along the gradient's sector, then hysteresis. The gradient and the suppression do not depend on the thresholds,
     * so they are computed once per image and each [edges] call costs only its hysteresis.
     */
    class Canny(src: ByteArray, private val width: Int, private val height: Int) {
        private val mag = IntArray(width * height)

        /** Whether each pixel is a local maximum of the magnitude across its edge (before the low threshold). */
        private val localMax = BooleanArray(width * height)

        /** Hysteresis buffers, reused by every [edges] call: 0 = not an edge, 1 = weak (a local maximum above low), 2 = edge. */
        private val state = ByteArray(width * height)
        private val stack = IntArray(width * height)

        init {
            val dxs = IntArray(width * height)
            val dys = IntArray(width * height)
            for (y in 0 until height) {
                val up = (y - 1).coerceAtLeast(0) * width
                val mid = y * width
                val down = (y + 1).coerceAtMost(height - 1) * width
                for (x in 0 until width) {
                    val l = (x - 1).coerceAtLeast(0)
                    val r = (x + 1).coerceAtMost(width - 1)
                    val dx = src.u8(up + r) + 2 * src.u8(mid + r) + src.u8(down + r) - src.u8(up + l) - 2 * src.u8(mid + l) - src.u8(down + l)
                    val dy = src.u8(down + l) + 2 * src.u8(down + x) + src.u8(down + r) - src.u8(up + l) - 2 * src.u8(up + x) - src.u8(up + r)
                    dxs[mid + x] = dx
                    dys[mid + x] = dy
                    mag[mid + x] = abs(dx) + abs(dy)
                }
            }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    val m = mag[i]
                    if (m == 0) continue
                    val xs = dxs[i]
                    val ys = dys[i]
                    val ax = abs(xs)
                    val ay = abs(ys) shl CANNY_SHIFT
                    val tg22x = ax * TG22
                    localMax[i] = if (ay < tg22x) {
                        m > magAt(x - 1, y) && m >= magAt(x + 1, y)
                    } else if (ay > tg22x + (ax shl (CANNY_SHIFT + 1))) {
                        m > magAt(x, y - 1) && m >= magAt(x, y + 1)
                    } else {
                        val s = if ((xs xor ys) < 0) -1 else 1
                        m > magAt(x - s, y - 1) && m > magAt(x + s, y + 1)
                    }
                }
            }
        }

        private fun magAt(x: Int, y: Int): Int = if (x < 0 || y < 0 || x >= width || y >= height) 0 else mag[y * width + x]

        /** The 0/1 edge mask: local maxima above [low] that are 8-connected to one above [high]. */
        fun edges(low: Int, high: Int): ByteArray {
            val n = width * height
            state.fill(0)
            var top = 0
            for (i in 0 until n) {
                val m = mag[i]
                if (m <= low || !localMax[i]) continue
                if (m > high) {
                    state[i] = 2
                    stack[top++] = i
                } else {
                    state[i] = 1
                }
            }
            while (top > 0) {
                val i = stack[--top]
                val x = i % width
                val y = i / width
                for (ny in max(0, y - 1)..min(height - 1, y + 1)) {
                    for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                        val j = ny * width + nx
                        if (state[j].toInt() == 1) {
                            state[j] = 2
                            stack[top++] = j
                        }
                    }
                }
            }
            val out = ByteArray(n)
            for (i in 0 until n) {
                if (state[i].toInt() == 2) out[i] = 1
            }
            return out
        }
    }

    private const val CANNY_SHIFT = 15

    /** tan(22.5 degrees) in CANNY_SHIFT fixed point, as OpenCV's Canny uses it to pick the gradient's sector. */
    private const val TG22 = 13573
}
