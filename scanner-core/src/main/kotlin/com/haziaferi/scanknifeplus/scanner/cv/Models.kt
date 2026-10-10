// Ported from OpenScan lib/core/cv/models/ (point.dart, quad.dart, detection_result.dart).
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.cv

import kotlin.math.sqrt

/** A simple 2D point used by the scanner pipeline. */
data class Pt(val x: Double, val y: Double) {
    fun scaled(sx: Double, sy: Double): Pt = Pt(x * sx, y * sy)
}

/**
 * A quadrilateral with corners in a single canonical order everywhere in the app: clockwise starting at the top-left.
 * This is the only representation of a detected/edited document boundary that crosses any layer boundary (detector -> crop UI -> perspective warp).
 */
data class Quad(
    val topLeft: Pt,
    val topRight: Pt,
    val bottomRight: Pt,
    val bottomLeft: Pt,
) {
    fun scaled(sx: Double, sy: Double): Quad = Quad(
        topLeft = topLeft.scaled(sx, sy),
        topRight = topRight.scaled(sx, sy),
        bottomRight = bottomRight.scaled(sx, sy),
        bottomLeft = bottomLeft.scaled(sx, sy),
    )

    val points: List<Pt> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

/** The corners as `[tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y]`. */
internal fun Quad.toScalars(): DoubleArray = doubleArrayOf(
    topLeft.x, topLeft.y,
    topRight.x, topRight.y,
    bottomRight.x, bottomRight.y,
    bottomLeft.x, bottomLeft.y,
)

/** The inverse of [toScalars], with every scalar divided by [divisor] (a corner-wise sum's count). */
internal fun quadOf(s: DoubleArray, divisor: Int = 1): Quad = Quad(
    topLeft = Pt(s[0] / divisor, s[1] / divisor),
    topRight = Pt(s[2] / divisor, s[3] / divisor),
    bottomRight = Pt(s[4] / divisor, s[5] / divisor),
    bottomLeft = Pt(s[6] / divisor, s[7] / divisor),
)

/** Euclidean distance. OpenScan uses pow(d, 2); squaring by multiplication gives the same correctly rounded result. */
internal fun dist(a: Pt, b: Pt): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/** Result of running document-boundary detection on an image: an explicit three-way outcome so the UI is never left waiting. */
sealed class DetectionResult {
    /** A convex document-shaped quadrilateral was found. */
    data class Success(val quad: Quad, val imageWidth: Int, val imageHeight: Int) : DetectionResult()

    /** Detection ran without error, but no suitable quad was found. */
    data class NotFound(val imageWidth: Int, val imageHeight: Int) : DetectionResult()

    /** Detection threw (corrupt image, decode failure, etc). */
    data class Failure(val message: String) : DetectionResult()
}
