// Ported from OpenScan lib/core/cv/perspective_crop.dart, plus the parts of the Dart `image` package (4.2.0, MIT) it calls:
// copyResize with Interpolation.average and copyRotate by quarter turns.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.dartClamp
import com.haziaferi.scanknifeplus.scanner.dartFloor
import com.haziaferi.scanknifeplus.scanner.dartRound
import com.haziaferi.scanknifeplus.scanner.dartToInt
import com.haziaferi.scanknifeplus.scanner.u8
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** The pixel size of a warped page. */
data class PageSize(val width: Int, val height: Int)

/**
 * Warps the quad region of a photo into an upright rectangle: a direct-linear-transform homography solve and a bilinear-sampled inverse warp,
 * standing in for OpenCV's `getPerspectiveTransform` + `warpPerspective`.
 */
object PerspectiveCrop {
    /**
     * Maps a quad in fractional [0,1] portrait overlay coordinates (the live-scan overlay's space) onto a [width] x [height] image's pixel grid,
     * rotating it back into the photo's orientation first if the photo is landscape, so the corners aren't stretched across the wrong axes.
     */
    fun quadInPixelsOf(normalized: Quad, width: Int, height: Int): Quad {
        var quad = normalized
        if (width > height) {
            // Inverse of the live-scan overlay's fixed 90-degree rotation, in normalized space: portrait (x, y) came from sensor (y, 1 - x).
            quad = Contours.sortCorners(quad.points.map { Pt(it.y, 1 - it.x) })
        }
        return quad.scaled(width.toDouble(), height.toDouble())
    }

    /** The size an unscaled warp of [quad] produces: the longest of each pair of opposite edges, so no part of the page is squeezed. */
    fun outputSize(quad: Quad): PageSize {
        val tl = quad.topLeft
        val tr = quad.topRight
        val br = quad.bottomRight
        val bl = quad.bottomLeft
        val width = max(dist(tl.x, tl.y, tr.x, tr.y), dist(bl.x, bl.y, br.x, br.y))
        val height = max(dist(tl.x, tl.y, bl.x, bl.y), dist(tr.x, tr.y, br.x, br.y))
        return PageSize(dartRound(width).coerceIn(1, 1 shl 16), dartRound(height).coerceIn(1, 1 shl 16))
    }

    /**
     * Warps [quad] (in [decoded]'s pixel coordinates) into an upright rectangle capped at [maxEdge] on its long side. The cap is applied to the warp
     * itself (one sample per output pixel); where that throws away more than half the detail, the source is box-filtered down first so the result
     * is averaged rather than point-sampled. Returns null if the warp fails.
     */
    fun warpToPage(decoded: RgbaImage, quad: Quad, maxEdge: Int? = null): RgbaImage? {
        var source = decoded
        var pixels = quad

        val natural = outputSize(pixels)
        var outWidth = natural.width
        var outHeight = natural.height

        if (maxEdge != null && maxEdge > 0) {
            val longest = max(outWidth, outHeight)
            if (longest > maxEdge) {
                val scale = maxEdge.toDouble() / longest
                outWidth = max(1, dartRound(outWidth * scale))
                outHeight = max(1, dartRound(outHeight * scale))
                if (scale <= 0.5) {
                    source = averageResize(
                        decoded,
                        max(1, dartRound(decoded.width * scale)),
                        max(1, dartRound(decoded.height * scale)),
                    )
                    pixels = pixels.scaled(scale, scale)
                }
            }
        }

        return warp(source, pixels, outWidth, outHeight)
    }

    /**
     * Warps [quad] at its natural size and turns the result clockwise by [quarterTurns]: the pixels OpenScan's crop step writes back to the page
     * (it then encodes them as a quality-100 JPEG). Returns null if the warp fails.
     */
    fun cropToPage(decoded: RgbaImage, quad: Quad, quarterTurns: Int = 0): RgbaImage? {
        val warped = warp(decoded, quad, null, null) ?: return null
        return if (quarterTurns % 4 != 0) rotateQuarterTurns(warped, quarterTurns) else warped
    }

    /** Inverse-samples [quad] out of [decoded] into an upright [width] x [height] rectangle (both default to the quad's own size); null on failure. */
    fun warp(decoded: RgbaImage, quad: Quad, width: Int?, height: Int?): RgbaImage? = try {
        val srcWidth = decoded.width
        val srcHeight = decoded.height
        val srcRgba = decoded.pixels

        val natural = outputSize(quad)
        val outWidth = width ?: natural.width
        val outHeight = height ?: natural.height

        // Homography mapping output-rectangle coordinates -> source quad coordinates, used to inverse-sample the source for each output pixel.
        val h = solveHomography(outWidth.toDouble(), outHeight.toDouble(), quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)

        // Long so a large page cannot wrap to a small or negative size; past the JVM's array limit, fail into null like Dart's catch-all would.
        val byteCount = outWidth.toLong() * outHeight * 4
        require(byteCount <= Int.MAX_VALUE - 8) { "Output too large: $outWidth x $outHeight" }
        val outRgba = ByteArray(byteCount.toInt())
        val sampled = IntArray(4)
        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val denom = h[6] * x + h[7] * y + 1
                val sx = (h[0] * x + h[1] * y + h[2]) / denom
                val sy = (h[3] * x + h[4] * y + h[5]) / denom
                bilinearSample(srcRgba, srcWidth, srcHeight, sx, sy, sampled)
                val dstIdx = (y * outWidth + x) * 4
                outRgba[dstIdx] = sampled[0].toByte()
                outRgba[dstIdx + 1] = sampled[1].toByte()
                outRgba[dstIdx + 2] = sampled[2].toByte()
                outRgba[dstIdx + 3] = 255.toByte()
            }
        }
        RgbaImage(outWidth, outHeight, outRgba)
    } catch (e: Exception) {
        null
    }

    /** Turns [src] clockwise by [quarterTurns] quarter turns (the `image` package's `copyRotate` at multiples of 90 degrees). */
    internal fun rotateQuarterTurns(src: RgbaImage, quarterTurns: Int): RgbaImage {
        val turns = ((quarterTurns % 4) + 4) % 4
        val pixels = src.pixels
        if (turns == 0) return RgbaImage(src.width, src.height, pixels.copyOf())
        val w = src.width
        val h = src.height
        val dstW = if (turns == 2) w else h
        val dstH = if (turns == 2) h else w
        val out = ByteArray(dstW * dstH * 4)
        var o = 0
        when (turns) {
            1 -> for (y in 0 until dstH) for (x in 0 until dstW) {
                copyPixel(pixels, ((h - 1 - x) * w + y) * 4, out, o)
                o += 4
            }
            2 -> for (y in 0 until dstH) for (x in 0 until dstW) {
                copyPixel(pixels, ((h - 1 - y) * w + w - 1 - x) * 4, out, o)
                o += 4
            }
            else -> for (y in 0 until dstH) for (x in 0 until dstW) {
                copyPixel(pixels, (x * w + w - 1 - y) * 4, out, o)
                o += 4
            }
        }
        return RgbaImage(dstW, dstH, out)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun copyPixel(src: ByteArray, si: Int, dst: ByteArray, di: Int) {
        dst[di] = src[si]
        dst[di + 1] = src[si + 1]
        dst[di + 2] = src[si + 2]
        dst[di + 3] = src[si + 3]
    }

    /**
     * Box-averaging resize (the `image` package's `copyResize(..., interpolation: Interpolation.average)`): each output pixel is the truncated
     * mean of the source block it covers.
     */
    internal fun averageResize(src: RgbaImage, width: Int, height: Int): RgbaImage {
        if (width == src.width && height == src.height) return RgbaImage(width, height, src.pixels.copyOf())
        val out = ByteArray(width * height * 4)
        val dy = src.height.toDouble() / height
        val dx = src.width.toDouble() / width
        for (y in 0 until height) {
            val ay1 = dartToInt(y * dy)
            var ay2 = dartToInt((y + 1) * dy)
            if (ay2 == ay1) ay2++
            for (x in 0 until width) {
                val ax1 = dartToInt(x * dx)
                var ax2 = dartToInt((x + 1) * dx)
                if (ax2 == ax1) ax2++
                var r = 0L
                var g = 0L
                var b = 0L
                var a = 0L
                var np = 0
                for (sy in ay1 until ay2) {
                    for (sx in ax1 until ax2) {
                        val i = (sy * src.width + sx) * 4
                        r += src.pixels.u8(i)
                        g += src.pixels.u8(i + 1)
                        b += src.pixels.u8(i + 2)
                        a += src.pixels.u8(i + 3)
                        np++
                    }
                }
                val o = (y * width + x) * 4
                out[o] = dartToInt((r.toDouble() / np).dartClamp(0.0, 255.0)).toByte()
                out[o + 1] = dartToInt((g.toDouble() / np).dartClamp(0.0, 255.0)).toByte()
                out[o + 2] = dartToInt((b.toDouble() / np).dartClamp(0.0, 255.0)).toByte()
                out[o + 3] = dartToInt((a.toDouble() / np).dartClamp(0.0, 255.0)).toByte()
            }
        }
        return RgbaImage(width, height, out)
    }

    // The original uses pow(d, 2); squaring by multiplication gives the same correctly rounded result.
    private fun dist(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Solves the 8-parameter homography mapping the destination rectangle `(0,0)-(w,0)-(w,h)-(0,h)` onto the `(tl,tr,br,bl)` source quad via a
     * direct linear transform, in the direction needed for inverse mapping (output pixel -> source sample point).
     */
    internal fun solveHomography(w: Double, h: Double, tl: Pt, tr: Pt, br: Pt, bl: Pt): DoubleArray {
        val dst = arrayOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(w, 0.0), doubleArrayOf(w, h), doubleArrayOf(0.0, h))
        val src = arrayOf(doubleArrayOf(tl.x, tl.y), doubleArrayOf(tr.x, tr.y), doubleArrayOf(br.x, br.y), doubleArrayOf(bl.x, bl.y))

        // 8x8 linear system A*p = b for unknowns [a, b, c, d, e, f, g, h] where:
        //   x = (a*u + b*v + c) / (g*u + h*v + 1)
        //   y = (d*u + e*v + f) / (g*u + h*v + 1)
        val a = Array(8) { DoubleArray(8) }
        val bVec = DoubleArray(8)
        for (i in 0 until 4) {
            val u = dst[i][0]
            val v = dst[i][1]
            val x = src[i][0]
            val y = src[i][1]
            a[2 * i] = doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x)
            bVec[2 * i] = x
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y)
            bVec[2 * i + 1] = y
        }

        val p = solveLinearSystem(a, bVec)
        return p + 1.0
    }

    /** Gaussian elimination with partial pivoting for a small dense system. */
    private fun solveLinearSystem(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) {
                if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
            }
            val tmpRow = a[col]
            a[col] = a[pivot]
            a[pivot] = tmpRow
            val tmpB = b[col]
            b[col] = b[pivot]
            b[pivot] = tmpB

            val pivotVal = a[col][col]
            if (abs(pivotVal) < 1e-12) continue

            for (row in 0 until n) {
                if (row == col) continue
                val factor = a[row][col] / pivotVal
                if (factor == 0.0) continue
                for (c in col until n) {
                    a[row][c] -= factor * a[col][c]
                }
                b[row] -= factor * b[col]
            }
        }

        return DoubleArray(n) { i -> if (abs(a[i][i]) < 1e-12) 0.0 else b[i] / a[i][i] }
    }

    /** Bilinear sample at (x, y), clamped to the image; writes RGBA into [out]. Channels are rounded after each lerp, like the original. */
    private fun bilinearSample(rgba: ByteArray, width: Int, height: Int, x: Double, y: Double, out: IntArray) {
        val cx = x.dartClamp(0.0, width - 1.0)
        val cy = y.dartClamp(0.0, height - 1.0)

        val x0 = dartFloor(cx)
        val y0 = dartFloor(cy)
        val x1 = (x0 + 1).coerceIn(0, width - 1)
        val y1 = (y0 + 1).coerceIn(0, height - 1)

        val fx = cx - x0
        val fy = cy - y0

        val i00 = (y0 * width + x0) * 4
        val i10 = (y0 * width + x1) * 4
        val i01 = (y1 * width + x0) * 4
        val i11 = (y1 * width + x1) * 4
        for (c in 0 until 4) {
            val p00 = rgba.u8(i00 + c)
            val top = dartRound(p00 + (rgba.u8(i10 + c) - p00) * fx)
            val p01 = rgba.u8(i01 + c)
            val bottom = dartRound(p01 + (rgba.u8(i11 + c) - p01) * fx)
            out[c] = dartRound(top + (bottom - top) * fy)
        }
    }
}
