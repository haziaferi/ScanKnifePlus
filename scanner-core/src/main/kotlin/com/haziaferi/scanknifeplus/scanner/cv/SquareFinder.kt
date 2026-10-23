// Ported from OSS-DocumentScanner (https://github.com/ossappscollective/OSS-DocumentScanner) at commit ff05fc52b601d1cccb4076f3f6c3318f74e72f0c:
// cpp/src/DocumentDetector.cpp (scanPoint, findSquares, angle, getContourSortFactor) with the DetectOptions defaults of DocumentDetector.h.
// MIT License, Copyright (c) 2022 Martin Guillon. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.cv

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** A quad OSS-DocumentScanner's `findSquares` accepted: corners in contour order, the contour's area, its largest |cos| and its mask's weight. */
internal class Square(val corners: IntArray, val area: Double, val maxCosine: Double, val weight: Int) {
    /** `getContourSortFactor`: area plus weight times squareness. The weight term dominates, so the most rectangular quad wins. */
    val sortFactor: Double get() = area + weight * (1 - maxCosine)

    /** The corners less the working image's [offset] (its border), scaled by [sx] and [sy], in canonical order. */
    fun toQuad(offset: Int, sx: Double, sy: Double): Quad = Contours.sortCorners(
        List(4) { Pt((corners[2 * it] - offset) * sx, (corners[2 * it + 1] - offset) * sy) },
    )
}

/**
 * OSS-DocumentScanner's candidate generation: on each channel, a brightness threshold mask, then Canny masks from strict to loose, each
 * searched for convex, near-rectangular quads. The image carries a black border, so a page cut off by the frame still has an edge along it.
 */
internal object SquareFinder {
    /** Black border (px) added around the working image; a corner must lie inside it, so quads touching the frame edge still close. */
    const val BORDER = 10

    /** Brightness threshold of the first mask per channel (`thresh`). */
    private const val THRESHOLD = 160

    /** `morphologyAnchorSize` (closing of the threshold mask) and `dilateAnchorSize` (dilation of every mask). */
    private const val CLOSE_SIZE = 4
    private const val DILATE_SIZE = 3

    /** Canny thresholds are `t * CANNY_FACTOR` and twice that, for t from 60 down to 10 in steps of 10. */
    private const val CANNY_FACTOR = 2

    /** A quad needs at least this share of the bordered frame's area (the app's `areaScaleMinFactor`) and a contour of 100 px. */
    private const val MIN_AREA_FACTOR = 0.1
    private const val MIN_ARC_LENGTH = 100.0

    /** A contour covering 92% of the frame inside the border or more is the frame itself, not a page. */
    private const val MAX_AREA_FACTOR = 0.92

    /** Squareness needed to be a candidate (`expectedMaxCosine`), and to stop early with a large enough one (`expectedOptimalMaxCosine`). */
    private const val MAX_COSINE = 0.4
    private const val OPTIMAL_MAX_COSINE = 0.3
    private const val OPTIMAL_AREA_FACTOR = 0.2

    /** approxPolyDP epsilon as a fraction of the contour's length (`contoursApproxEpsilonFactor`). */
    private const val APPROX_EPSILON_FACTOR = 0.02

    /** Weight of the first mask's squares in [Square.sortFactor]; each later mask's is one less. */
    private const val FIRST_WEIGHT = 3_000_000

    /**
     * Runs `scanPoint`'s mask loop over [planes] (already bordered and median-blurred, each [width] x [height]) in the order given, stopping
     * once the best square is square and large enough. Returns the best square by [Square.sortFactor], or null if none was found.
     */
    fun findBest(planes: List<ByteArray>, width: Int, height: Int): Square? {
        val found = ArrayList<Square>()
        var weight = FIRST_WEIGHT

        fun goodEnough(mask: ByteArray): Boolean {
            collectSquares(EdgeMasks.rectFilter(mask, width, height, DILATE_SIZE, dilate = true), width, height, weight--, found)
            val best = found.maxByOrNull { it.sortFactor } ?: return false
            return best.maxCosine < OPTIMAL_MAX_COSINE && best.area > width.toDouble() * height * OPTIMAL_AREA_FACTOR
        }

        search@ for (plane in planes) {
            if (goodEnough(EdgeMasks.close(EdgeMasks.thresholdAbove(plane, THRESHOLD), width, height, CLOSE_SIZE))) break
            val canny = EdgeMasks.Canny(plane, width, height)
            for (t in 60 downTo 10 step 10) {
                if (goodEnough(canny.edges(t * CANNY_FACTOR, t * CANNY_FACTOR * 2))) break@search
            }
        }
        return found.maxByOrNull { it.sortFactor }
    }

    private fun collectSquares(mask: ByteArray, width: Int, height: Int, weight: Int, out: MutableList<Square>) {
        val minArea = width.toDouble() * height * MIN_AREA_FACTOR
        val maxArea = (width - 2.0 * BORDER) * (height - 2.0 * BORDER) * MAX_AREA_FACTOR
        for (contour in ContourTracing.findContours(mask, width, height)) {
            val arc = ContourTracing.arcLength(contour)
            val area = ContourTracing.area(contour)
            if (arc < MIN_ARC_LENGTH || area < minArea || area >= maxArea) continue
            val approx = ContourTracing.approxPolyDP(contour, arc * APPROX_EPSILON_FACTOR)
            if (approx.size != 8 || !ContourTracing.isConvex(approx)) continue
            var inside = true
            for (k in 0 until 4) {
                val x = approx[2 * k]
                val y = approx[2 * k + 1]
                if (x < BORDER || x >= width - BORDER || y < BORDER || y >= height - BORDER) inside = false
            }
            if (!inside) continue
            val maxCosine = maxCosine(approx)
            if (maxCosine < MAX_COSINE) out += Square(approx, area, maxCosine, weight)
        }
    }

    /** The largest |cos| of the four corner angles, corners in contour order (findSquares' loop over `angle`). */
    fun maxCosine(c: IntArray): Double {
        var m = 0.0
        for (j in 2 until 6) {
            val p1 = j % 4
            val p2 = j - 2
            val p0 = (j - 1) % 4
            val dx1 = (c[2 * p1] - c[2 * p0]).toDouble()
            val dy1 = (c[2 * p1 + 1] - c[2 * p0 + 1]).toDouble()
            val dx2 = (c[2 * p2] - c[2 * p0]).toDouble()
            val dy2 = (c[2 * p2 + 1] - c[2 * p0 + 1]).toDouble()
            m = max(m, abs((dx1 * dx2 + dy1 * dy2) / sqrt((dx1 * dx1 + dy1 * dy1) * (dx2 * dx2 + dy2 * dy2) + 1e-10)))
        }
        return m
    }
}

/**
 * The contour operations `findSquares` calls from OpenCV: `findContours(RETR_TREE, CHAIN_APPROX_SIMPLE)` (Suzuki-Abe border following),
 * `arcLength`, `contourArea`, `approxPolyDP` and `isContourConvex`. Contours are flat `[x0, y0, x1, y1, ...]` integer arrays.
 */
internal object ContourTracing {
    /** Neighbour offsets counter-clockwise on screen from east, the direction numbering of Suzuki-Abe's border following. */
    private val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val DY = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)

    /**
     * Every outer and hole border of the 0/1 [mask], each compressed to the points where its direction changes. Pixels outside the mask
     * count as 0.
     */
    fun findContours(mask: ByteArray, width: Int, height: Int): List<IntArray> {
        val w = width + 2
        val f = IntArray(w * (height + 2))
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (mask[y * width + x].toInt() != 0) f[(y + 1) * w + x + 1] = 1
            }
        }
        val offsets = IntArray(8) { DY[it] * w + DX[it] }
        val contours = ArrayList<IntArray>()
        var nbd = 1
        var chain = IntArray(1024)
        for (y in 1..height) {
            for (x in 1..width) {
                val p = y * w + x
                val v = f[p]
                if (v == 0) continue
                val startDir = when {
                    v == 1 && f[p - 1] == 0 -> 4
                    v >= 1 && f[p + 1] == 0 -> 0
                    else -> -1
                }
                if (startDir < 0) continue
                nbd++
                var n = 0

                fun add(q: Int) {
                    if (n + 2 > chain.size) chain = chain.copyOf(chain.size * 2)
                    chain[n++] = q % w - 1
                    chain[n++] = q / w - 1
                }

                // Clockwise from the start direction for the first non-zero neighbour.
                var d1 = -1
                for (k in 0 until 8) {
                    val d = (startDir - k + 8) % 8
                    if (f[p + offsets[d]] != 0) {
                        d1 = d
                        break
                    }
                }
                if (d1 < 0) {
                    f[p] = -nbd
                    add(p)
                    contours += chain.copyOf(n)
                    continue
                }
                val p1 = p + offsets[d1]
                var cur = p
                var dirToPrev = d1
                while (true) {
                    add(cur)
                    // Counter-clockwise from the neighbour after the previous pixel for the next non-zero neighbour.
                    var eastZero = false
                    var dNext = -1
                    for (k in 1..8) {
                        val d = (dirToPrev + k) % 8
                        if (f[cur + offsets[d]] != 0) {
                            dNext = d
                            break
                        }
                        if (d == 0) eastZero = true
                    }
                    if (eastZero) {
                        f[cur] = -nbd
                    } else if (f[cur] == 1) {
                        f[cur] = nbd
                    }
                    val next = cur + offsets[dNext]
                    if (next == p && cur == p1) break
                    dirToPrev = (dNext + 4) % 8
                    cur = next
                }
                contours += compress(chain, n)
            }
        }
        return contours
    }

    /** CHAIN_APPROX_SIMPLE: keeps the first point and every point where the step direction changes. */
    private fun compress(chain: IntArray, n: Int): IntArray {
        val count = n / 2
        if (count < 3) return chain.copyOf(n)
        val out = IntArray(n)
        var m = 0
        for (i in 0 until count) {
            val a = (i - 1 + count) % count
            val b = (i + 1) % count
            val inX = chain[2 * i] - chain[2 * a]
            val inY = chain[2 * i + 1] - chain[2 * a + 1]
            val outX = chain[2 * b] - chain[2 * i]
            val outY = chain[2 * b + 1] - chain[2 * i + 1]
            if (i == 0 || inX != outX || inY != outY) {
                out[m++] = chain[2 * i]
                out[m++] = chain[2 * i + 1]
            }
        }
        return out.copyOf(m)
    }

    /** Perimeter of the closed contour. */
    fun arcLength(c: IntArray): Double {
        val count = c.size / 2
        var sum = 0.0
        for (i in 0 until count) {
            val j = (i + 1) % count
            val dx = (c[2 * j] - c[2 * i]).toDouble()
            val dy = (c[2 * j + 1] - c[2 * i + 1]).toDouble()
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum
    }

    /** Unsigned shoelace area of the closed contour. */
    fun area(c: IntArray): Double {
        val count = c.size / 2
        var sum = 0L
        for (i in 0 until count) {
            val j = (i + 1) % count
            sum += c[2 * i].toLong() * c[2 * j + 1] - c[2 * j].toLong() * c[2 * i + 1]
        }
        return abs(sum) / 2.0
    }

    /**
     * Douglas-Peucker simplification of a closed contour as OpenCV's `approxPolyDP(closed = true)` does it: start from an approximate
     * farthest pair (three rounds of "farthest from the current start"), split both halves while a point lies more than [epsilon] from the
     * chord's line, then drop points that lie almost on the line between their neighbours.
     */
    fun approxPolyDP(c: IntArray, epsilon: Double): IntArray {
        val count = c.size / 2
        if (count == 0) return IntArray(0)
        val eps = epsilon * epsilon
        fun x(i: Int) = c[2 * i].toDouble()
        fun y(i: Int) = c[2 * i + 1].toDouble()

        var start = 0
        var farOffset = 0
        var maxDist = 0.0
        for (round in 0 until 3) {
            start = (start + farOffset) % count
            maxDist = 0.0
            farOffset = 0
            for (j in 1 until count) {
                val p = (start + j) % count
                val dx = x(p) - x(start)
                val dy = y(p) - y(start)
                val dist = dx * dx + dy * dy
                if (dist > maxDist) {
                    maxDist = dist
                    farOffset = j
                }
            }
        }
        val kept = ArrayList<Int>()
        if (maxDist <= eps) {
            kept += start
        } else {
            val far = (start + farOffset) % count
            // Slices are [from, to] index pairs on the cyclic contour; the left half is popped first so the output stays in contour order.
            val stack = ArrayList<IntArray>()
            stack += intArrayOf(far, start)
            stack += intArrayOf(start, far)
            while (stack.isNotEmpty()) {
                val (from, to) = stack.removeAt(stack.size - 1).let { it[0] to it[1] }
                var pos = (from + 1) % count
                var split = -1
                var withinEps = true
                if (pos != to) {
                    val dx = x(to) - x(from)
                    val dy = y(to) - y(from)
                    var best = 0.0
                    while (pos != to) {
                        val d = abs((y(pos) - y(from)) * dx - (x(pos) - x(from)) * dy)
                        if (d > best) {
                            best = d
                            split = pos
                        }
                        pos = (pos + 1) % count
                    }
                    withinEps = best * best <= eps * (dx * dx + dy * dy)
                }
                if (withinEps) {
                    kept += from
                } else {
                    stack += intArrayOf(split, to)
                    stack += intArrayOf(from, split)
                }
            }
        }
        return removeCollinear(kept.flatMap { listOf(c[2 * it], c[2 * it + 1]) }.toIntArray(), eps)
    }

    /** approxPolyDP's last stage: drops a point that lies within half the tolerance of the line through its neighbours, between them. */
    private fun removeCollinear(d: IntArray, eps: Double): IntArray {
        val count = d.size / 2
        if (count <= 2) return d
        val pts = d.copyOf()
        var newCount = count
        var pos = count - 1
        fun read(): Int {
            val p = pos
            pos = (pos + 1) % count
            return p
        }
        var s = read()
        var sx = pts[2 * s]
        var sy = pts[2 * s + 1]
        var wpos = pos
        var pt = read()
        var px = pts[2 * pt]
        var py = pts[2 * pt + 1]
        var i = 0
        while (i < count && newCount > 2) {
            val e = read()
            val ex = pts[2 * e]
            val ey = pts[2 * e + 1]
            val dx = (ex - sx).toDouble()
            val dy = (ey - sy).toDouble()
            val dist = abs((px - sx) * dy - (py - sy) * dx)
            val inner = (px - sx).toDouble() * (ex - px) + (py - sy).toDouble() * (ey - py)
            if (dist * dist <= 0.5 * eps * (dx * dx + dy * dy) && dx != 0.0 && dy != 0.0 && inner >= 0) {
                newCount--
                pts[2 * wpos] = ex
                pts[2 * wpos + 1] = ey
                sx = ex
                sy = ey
                wpos = (wpos + 1) % count
                pt = read()
                px = pts[2 * pt]
                py = pts[2 * pt + 1]
                i += 2
                continue
            }
            pts[2 * wpos] = px
            pts[2 * wpos + 1] = py
            sx = px
            sy = py
            wpos = (wpos + 1) % count
            px = ex
            py = ey
            i++
        }
        return pts.copyOf(2 * newCount)
    }

    /** OpenCV's `isContourConvex`: every turn has the same, non-zero orientation. */
    fun isConvex(c: IntArray): Boolean {
        val count = c.size / 2
        var orientation = 0
        var dx0 = c[0] - c[2 * (count - 1)]
        var dy0 = c[1] - c[2 * (count - 1) + 1]
        for (i in 0 until count) {
            val j = (i + 1) % count
            val dx = c[2 * j] - c[2 * i]
            val dy = c[2 * j + 1] - c[2 * i + 1]
            val dxdy0 = dx.toLong() * dy0
            val dydx0 = dy.toLong() * dx0
            orientation = orientation or if (dydx0 > dxdy0) 1 else if (dydx0 < dxdy0) 2 else 3
            if (orientation == 3) return false
            dx0 = dx
            dy0 = dy
        }
        return true
    }
}
