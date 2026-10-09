// Ported from OpenScan lib/core/cv/contours.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.dartSort
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/** A quad matched to a reference, with the summed corner-to-corner distance of the match. */
data class CornerAssignment(val quad: Quad, val totalDistance: Double)

/** One cluster of candidates describing the same shape: their corner-wise mean and how many candidates it was built from. */
internal data class QuadCluster(val quad: Quad, val support: Int)

/**
 * Finds document-shaped quadrilaterals in a binary edge mask (1 = edge), analogous to OpenCV's `findContours` + `approxPolyDP` + `sortPoints` pipeline.
 */
object Contours {
    /**
     * Weight given to proximity to the previous quad (in [pickBestQuad]) versus intrinsic candidate quality (area + squareness). Deliberately small:
     * a strong pull toward the previous frame's position made the overlay chase poor candidates that merely sat close to last frame; this is a
     * tie-breaker for temporal consistency between otherwise similarly-good candidates, not the primary signal.
     */
    const val PREVIOUS_QUAD_PROXIMITY_WEIGHT = 0.15

    /** How far apart (average per-corner distance, as a fraction of the frame diagonal) two candidates may sit and still be treated as the same detection. */
    const val CANDIDATE_CLUSTER_FRACTION = 0.03

    /**
     * Weight given to how many candidates back a cluster. A shape that several thresholds and several RDP epsilons all independently agree on is far
     * more likely to be the real document edge than one that only a single parameter combination produced, and it is the one that will still be there next frame.
     */
    const val CANDIDATE_SUPPORT_WEIGHT = 0.12

    /** Minimum quad area as a fraction of the frame it was detected in; rejects noise-sized convex quads. */
    const val MIN_QUAD_AREA_RATIO = 0.05

    /**
     * Minimum interior angle, in degrees, considered legal for a document corner. A quad with an angle below this (or above `180 - MIN_QUAD_ANGLE_DEGREES`)
     * has a corner that has effectively collapsed onto its neighbours: a sliver or near-triangle, not a usable crop target.
     */
    const val MIN_QUAD_ANGLE_DEGREES = 15.0

    /**
     * Finds the best convex quadrilateral in [mask], scored by area weighted by how close the corners are to 90 degrees (see [pickBestQuad]).
     * Returns the quad in the mask's own coordinate space; the caller rescales it to the original image size.
     */
    fun findDocumentQuad(mask: ByteArray, width: Int, height: Int, previousQuad: Quad? = null): Quad? {
        val candidates = findDocumentQuadCandidates(mask, width, height)
        return pickBestQuad(candidates, width, height, previousQuad)
    }

    /**
     * Collects every candidate quad that passes [isPlausibleQuad] across the full connected-component x RDP-epsilon sweep of a binary edge mask.
     * Kept separate from [pickBestQuad] so the live-scan pipeline can pool candidates from several differently-thresholded masks before scoring.
     */
    fun findDocumentQuadCandidates(mask: ByteArray, width: Int, height: Int): List<Quad> {
        val components = connectedComponents(mask, width, height)
        components.dartSort { a, b -> b.size.compareTo(a.size) }

        val candidateCount = if (components.size < 5) components.size else 5
        val results = ArrayList<Quad>()

        for (i in 0 until candidateCount) {
            val members = components[i]
            if (members.size < 4) continue

            val hull = convexHull(members)
            if (hull.size < 4) continue

            for (epsilonFactor in EPSILON_FACTORS) {
                val simplified = if (hull.size <= 4) hull else simplifyClosedPolygon(hull, epsilonFactor)
                if (simplified.size == 4 && isConvex(simplified)) {
                    val quad = sortCorners(simplified)
                    if (isPlausibleQuad(quad, width, height)) {
                        results += quad
                    }
                }
            }
        }
        return results
    }

    /**
     * Picks the best detection from [candidates] (as gathered by [findDocumentQuadCandidates], possibly pooled from multiple masks).
     *
     * The pool is first clustered: candidates whose corners all sit within [CANDIDATE_CLUSTER_FRACTION] of each other are the same shape found several
     * times over and are averaged into one consensus quad, so two near-identical shapes can't trade places from frame to frame. Clusters are then scored
     * by area weighted by squareness, plus a bonus for how many candidates back the cluster ([CANDIDATE_SUPPORT_WEIGHT]), plus, if [previousQuad] is
     * supplied, a small proximity bonus ([PREVIOUS_QUAD_PROXIMITY_WEIGHT]) toward whatever was detected last frame.
     */
    fun pickBestQuad(candidates: List<Quad>, width: Int, height: Int, previousQuad: Quad? = null): Quad? {
        if (candidates.isEmpty()) return null

        val diagonal = sqrt((width * width + height * height).toDouble())
        val clusters = clusterCandidates(candidates, diagonal)

        var best: Quad? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (cluster in clusters) {
            var score = qualityScore(cluster.quad, width, height) +
                CANDIDATE_SUPPORT_WEIGHT * (cluster.support.toDouble() / candidates.size)
            var result = cluster.quad

            if (previousQuad != null) {
                val match = bestCornerAssignment(result.points, previousQuad)
                result = match.quad
                val proximity = if (diagonal == 0.0) 0.0 else 1 - (match.totalDistance / diagonal).coerceIn(0.0, 1.0)
                score += PREVIOUS_QUAD_PROXIMITY_WEIGHT * proximity
            }

            if (score > bestScore) {
                bestScore = score
                best = result
            }
        }
        return best
    }

    /**
     * Groups candidates that describe the same shape and averages each group corner-wise (greedy single pass against each group's running mean).
     */
    internal fun clusterCandidates(candidates: List<Quad>, diagonal: Double): List<QuadCluster> {
        val tolerance = diagonal * CANDIDATE_CLUSTER_FRACTION
        val means = ArrayList<Quad>()
        val sums = ArrayList<DoubleArray>()
        val counts = ArrayList<Int>()

        for (candidate in candidates) {
            var matched: Int? = null
            var aligned = candidate
            for (i in means.indices) {
                // Align corner labels to the cluster before averaging: two detections of the same physical shape can carry different labels
                // (a near-45-degree document), and averaging those slot-wise would produce a quad that is in neither of them.
                val match = bestCornerAssignment(candidate.points, means[i])
                if (match.totalDistance / 4 <= tolerance) {
                    matched = i
                    aligned = match.quad
                    break
                }
            }

            if (matched == null) {
                means += candidate
                sums += scalars(candidate)
                counts += 1
                continue
            }

            val sum = sums[matched]
            val s = scalars(aligned)
            for (i in 0 until 8) {
                sum[i] += s[i]
            }
            counts[matched] = counts[matched] + 1
            means[matched] = quadOfScalars(sum, counts[matched])
        }

        return means.indices.map { QuadCluster(means[it], counts[it]) }
    }

    private fun scalars(q: Quad): DoubleArray = doubleArrayOf(
        q.topLeft.x, q.topLeft.y,
        q.topRight.x, q.topRight.y,
        q.bottomRight.x, q.bottomRight.y,
        q.bottomLeft.x, q.bottomLeft.y,
    )

    private fun quadOfScalars(sums: DoubleArray, count: Int): Quad = Quad(
        topLeft = Pt(sums[0] / count, sums[1] / count),
        topRight = Pt(sums[2] / count, sums[3] / count),
        bottomRight = Pt(sums[4] / count, sums[5] / count),
        bottomLeft = Pt(sums[6] / count, sums[7] / count),
    )

    /** Area (as a fraction of the frame) weighted by squareness: larger, more rectangular quads score higher. */
    private fun qualityScore(quad: Quad, width: Int, height: Int): Double {
        val areaRatio = polygonArea(quad.points) / (width * height)
        return areaRatio * (1 - maxAngleDeviationFraction(quad))
    }

    /** Largest per-corner deviation from 90 degrees, normalized to [0,1] (0 = every corner is exactly 90 degrees; 1 = a corner is 0 or 180 degrees). */
    private fun maxAngleDeviationFraction(quad: Quad): Double {
        val pts = quad.points
        var maxDeviation = 0.0
        for (i in pts.indices) {
            val a = pts[(i - 1 + pts.size) % pts.size]
            val b = pts[i]
            val c = pts[(i + 1) % pts.size]
            val deviation = abs(angleAtVertexDegrees(a, b, c) - 90)
            if (deviation > maxDeviation) maxDeviation = deviation
        }
        return (maxDeviation / 90).coerceIn(0.0, 1.0)
    }

    /**
     * Assigns [points] (exactly 4, unordered) to the corner slots of [reference], choosing whichever of the 24 permutations minimizes the total
     * corner-to-corner distance. Keeps a physical corner mapped to the same slot across frames even when [sortCorners] would flip it near a 45-degree rotation.
     */
    fun bestCornerAssignment(points: List<Pt>, reference: Quad): CornerAssignment {
        require(points.size == 4)
        val refPts = reference.points

        var bestPerm: IntArray? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (perm in CORNER_PERMUTATIONS) {
            var total = 0.0
            for (i in 0 until 4) {
                total += dist(points[perm[i]], refPts[i])
            }
            if (total < bestDistance) {
                bestDistance = total
                bestPerm = perm
            }
        }

        val p = bestPerm!!
        return CornerAssignment(
            quad = Quad(
                topLeft = points[p[0]],
                topRight = points[p[1]],
                bottomRight = points[p[2]],
                bottomLeft = points[p[3]],
            ),
            totalDistance = bestDistance,
        )
    }

    /** 8-connected flood fill over the binary mask, returning member pixels per connected component (BFS via an index queue, safe on large masks). */
    private fun connectedComponents(mask: ByteArray, width: Int, height: Int): MutableList<List<Pt>> {
        val visited = BooleanArray(width * height)
        val components = ArrayList<List<Pt>>()
        val queue = IntArray(width * height)

        for (start in mask.indices) {
            if (mask[start].toInt() != 1 || visited[start]) continue

            val members = ArrayList<Pt>()
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var head = 0

            while (head < tail) {
                val idx = queue[head++]
                val x = idx % width
                val y = idx / width
                members += Pt(x.toDouble(), y.toDouble())

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue
                        val nIdx = ny * width + nx
                        if (mask[nIdx].toInt() == 1 && !visited[nIdx]) {
                            visited[nIdx] = true
                            queue[tail++] = nIdx
                        }
                    }
                }
            }
            components += members
        }
        return components
    }

    /** Andrew's monotone-chain convex hull. */
    private fun convexHull(points: List<Pt>): List<Pt> {
        val pts = points.toMutableList()
        pts.dartSort { a, b -> if (a.x != b.x) a.x.compareTo(b.x) else a.y.compareTo(b.y) }
        if (pts.size < 3) return pts

        fun cross(o: Pt, a: Pt, b: Pt): Double = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

        val lower = ArrayList<Pt>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower += p
        }

        val upper = ArrayList<Pt>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper += p
        }

        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /**
     * Ramer-Douglas-Peucker simplification of a closed polygon: splits at the two farthest-apart hull points into two open chains, simplifies each,
     * then merges; stands in for `approxPolyDP(3% arc length)`.
     */
    private fun simplifyClosedPolygon(hull: List<Pt>, epsilonFactor: Double): List<Pt> {
        var perimeter = 0.0
        for (i in hull.indices) {
            perimeter += dist(hull[i], hull[(i + 1) % hull.size])
        }
        val epsilon = epsilonFactor * perimeter

        var ai = 0
        var bi = 0
        var best = -1.0
        for (i in hull.indices) {
            for (j in i + 1 until hull.size) {
                val d = dist(hull[i], hull[j])
                if (d > best) {
                    best = d
                    ai = i
                    bi = j
                }
            }
        }

        fun chain(from: Int, to: Int): List<Pt> {
            val result = ArrayList<Pt>()
            var i = from
            while (true) {
                result += hull[i]
                if (i == to) break
                i = (i + 1) % hull.size
            }
            return result
        }

        val chain1 = rdp(chain(ai, bi), epsilon)
        val chain2 = rdp(chain(bi, ai), epsilon)

        return chain1 + chain2.subList(1, chain2.size - 1)
    }

    private fun rdp(points: List<Pt>, epsilon: Double): List<Pt> {
        if (points.size < 3) return points

        var maxDist = -1.0
        var index = 0
        val start = points.first()
        val end = points.last()

        for (i in 1 until points.size - 1) {
            val d = perpendicularDistance(points[i], start, end)
            if (d > maxDist) {
                maxDist = d
                index = i
            }
        }

        if (maxDist > epsilon) {
            val left = rdp(points.subList(0, index + 1), epsilon)
            val right = rdp(points.subList(index, points.size), epsilon)
            return left.subList(0, left.size - 1) + right
        }
        return listOf(start, end)
    }

    private fun perpendicularDistance(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = sqrt(dx * dx + dy * dy)
        if (len == 0.0) return dist(p, a)
        val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (len * len)
        val projX = a.x + t * dx
        val projY = a.y + t * dy
        return dist(p, Pt(projX, projY))
    }

    // The original uses pow(d, 2); squaring by multiplication gives the same correctly rounded result.
    private fun dist(a: Pt, b: Pt): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun isConvex(pts: List<Pt>): Boolean {
        val n = pts.size
        var positiveSign: Boolean? = null
        for (i in 0 until n) {
            val a = pts[i]
            val b = pts[(i + 1) % n]
            val c = pts[(i + 2) % n]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross == 0.0) continue
            val positive = cross > 0
            if (positiveSign == null) {
                positiveSign = positive
            } else if (positiveSign != positive) {
                return false
            }
        }
        return true
    }

    private fun polygonArea(pts: List<Pt>): Double {
        var area = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            area += a.x * b.y - b.x * a.y
        }
        return abs(area) / 2
    }

    /**
     * Rejects degenerate quads: too small relative to the frame, or so thin that a corner has collapsed (near-triangular). Public so any quad
     * (e.g. one that's been scaled after detection) can be re-validated with the same rule.
     */
    fun isPlausibleQuad(quad: Quad, width: Int, height: Int): Boolean {
        val pts = quad.points

        val area = polygonArea(pts)
        if (width <= 0 || height <= 0) return false
        if (area / (width * height) < MIN_QUAD_AREA_RATIO) return false

        for (i in pts.indices) {
            val a = pts[(i - 1 + pts.size) % pts.size]
            val b = pts[i]
            val c = pts[(i + 1) % pts.size]
            val angle = angleAtVertexDegrees(a, b, c)
            if (angle < MIN_QUAD_ANGLE_DEGREES || angle > 180 - MIN_QUAD_ANGLE_DEGREES) {
                return false
            }
        }
        return true
    }

    /** Interior angle at vertex [b], in degrees, formed by rays b->a and b->c. Returns 0 if either ray has zero length (maximally degenerate). */
    private fun angleAtVertexDegrees(a: Pt, b: Pt, c: Pt): Double {
        val abx = a.x - b.x
        val aby = a.y - b.y
        val cbx = c.x - b.x
        val cby = c.y - b.y
        val magAB = sqrt(abx * abx + aby * aby)
        val magCB = sqrt(cbx * cbx + cby * cby)
        if (magAB == 0.0 || magCB == 0.0) return 0.0
        val cosAngle = ((abx * cbx + aby * cby) / (magAB * magCB)).coerceIn(-1.0, 1.0)
        return acos(cosAngle) * 180 / Math.PI
    }

    /**
     * Canonical corner order: top-left / top-right / bottom-right / bottom-left, clockwise as seen on screen (y grows downward).
     *
     * 1. The four points are put in convex cyclic order by angle around their centroid, so the polygon is a simple quadrilateral wound clockwise.
     * 2. Of the four rotations of that cycle, the one whose labels best fit their names wins: top corners above bottom ones, right corners to the right
     *    of left ones. Rotating a cycle can never duplicate or drop a point (unlike a per-slot sum/difference sort), so the result is always a permutation of the input.
     */
    fun sortCorners(pts: List<Pt>): Quad {
        require(pts.size == 4)
        val cx = (pts[0].x + pts[1].x + pts[2].x + pts[3].x) / 4
        val cy = (pts[0].y + pts[1].y + pts[2].y + pts[3].y) / 4

        // Ascending angle around the centroid is clockwise on screen, since y grows downward.
        val ordered = pts.toMutableList()
        ordered.dartSort { a, b -> atan2(a.y - cy, a.x - cx).compareTo(atan2(b.y - cy, b.x - cx)) }

        var bestStart = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (start in 0 until 4) {
            val tl = ordered[start]
            val tr = ordered[(start + 1) % 4]
            val br = ordered[(start + 2) % 4]
            val bl = ordered[(start + 3) % 4]
            // How well this labelling agrees with what the names claim; the rotation that agrees most is the one a person would have drawn.
            val score = (tr.x - tl.x) + (br.x - bl.x) + (bl.y - tl.y) + (br.y - tr.y)
            if (score > bestScore) {
                bestScore = score
                bestStart = start
            }
        }

        return Quad(
            topLeft = ordered[bestStart],
            topRight = ordered[(bestStart + 1) % 4],
            bottomRight = ordered[(bestStart + 2) % 4],
            bottomLeft = ordered[(bestStart + 3) % 4],
        )
    }

    private val EPSILON_FACTORS = doubleArrayOf(0.02, 0.04, 0.06, 0.08, 0.1)

    /** All 24 permutations of [0,1,2,3], in the original's order (it decides ties). */
    private val CORNER_PERMUTATIONS: List<IntArray> = listOf(
        intArrayOf(0, 1, 2, 3), intArrayOf(0, 1, 3, 2), intArrayOf(0, 2, 1, 3), intArrayOf(0, 2, 3, 1),
        intArrayOf(0, 3, 1, 2), intArrayOf(0, 3, 2, 1), intArrayOf(1, 0, 2, 3), intArrayOf(1, 0, 3, 2),
        intArrayOf(1, 2, 0, 3), intArrayOf(1, 2, 3, 0), intArrayOf(1, 3, 0, 2), intArrayOf(1, 3, 2, 0),
        intArrayOf(2, 0, 1, 3), intArrayOf(2, 0, 3, 1), intArrayOf(2, 1, 0, 3), intArrayOf(2, 1, 3, 0),
        intArrayOf(2, 3, 0, 1), intArrayOf(2, 3, 1, 0), intArrayOf(3, 0, 1, 2), intArrayOf(3, 0, 2, 1),
        intArrayOf(3, 1, 0, 2), intArrayOf(3, 1, 2, 0), intArrayOf(3, 2, 0, 1), intArrayOf(3, 2, 1, 0),
    )
}
