// Ported from OpenScan lib/core/cv/models/ (point.dart, quad.dart, detection_result.dart).
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.cv

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

/** Result of running document-boundary detection on an image: an explicit three-way outcome so the UI is never left waiting. */
sealed class DetectionResult {
    /** A convex document-shaped quadrilateral was found. */
    data class Success(val quad: Quad, val imageWidth: Int, val imageHeight: Int) : DetectionResult()

    /** Detection ran without error, but no suitable quad was found. */
    data class NotFound(val imageWidth: Int, val imageHeight: Int) : DetectionResult()

    /** Detection threw (corrupt image, decode failure, timeout, etc). */
    data class Failure(val message: String) : DetectionResult()
}

/** Result of running the perspective crop. */
sealed class CropResult {
    data class Success(val path: String) : CropResult()

    data class Failure(val message: String) : CropResult()
}
