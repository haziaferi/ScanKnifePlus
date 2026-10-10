// Ported from OpenScan lib/core/cv/document_detector.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.dartRound
import kotlin.math.floor
import kotlin.math.max

/** Finds the document's corners in a photo: downscale, edge masks at several thresholds, pooled candidates, best quad. */
object DocumentDetector {
    /**
     * Longest edge (px) that detection runs at. Keeps the edge/contour pipeline fast on full-resolution camera photos; the resulting quad is scaled
     * back up, since [DetectionResult.Success.quad] is always in original-image coordinates.
     */
    const val DETECTION_MAX_DIMENSION = 700

    /**
     * Multipliers applied to the Otsu threshold to build several binarized edge masks per frame instead of trusting a single one. Otsu's threshold
     * shifts slightly frame to frame under sensor noise even when the scene hasn't changed, and a single mask's contour search can then land on a
     * visibly different quad; pooling the candidates from all masks and scoring them ([Contours.pickBestQuad]) avoids that.
     */
    private val THRESHOLD_MULTIPLIERS = doubleArrayOf(0.7, 1.0, 1.3)

    /**
     * Detects the document in a decoded RGBA image (stride 4) of [width] x [height]. Exceptions come back as [DetectionResult.Failure].
     *
     * This is the decode-independent part of OpenScan's `detectDocumentIsolateEntry`; the caller decodes the image file.
     */
    fun detectFromRgba(rgba: ByteArray, width: Int, height: Int): DetectionResult = try {
        val longestEdge = max(width, height).toDouble()
        val workingScale = if (longestEdge > DETECTION_MAX_DIMENSION) DETECTION_MAX_DIMENSION / longestEdge else 1.0
        val workWidth = max(1, dartRound(width * workingScale))
        val workHeight = max(1, dartRound(height * workingScale))

        val small = downscaleRgba(rgba, width, height, workWidth, workHeight)
        val gray = EdgeDetection.rgbaToGrayscale(small, workWidth, workHeight)
        val quad = detectQuadFromGrayscale(gray, workWidth, workHeight)
        if (quad == null) {
            DetectionResult.NotFound(width, height)
        } else {
            val scaleBackX = width.toDouble() / workWidth
            val scaleBackY = height.toDouble() / workHeight
            DetectionResult.Success(quad.scaled(scaleBackX, scaleBackY), width, height)
        }
    } catch (e: Exception) {
        DetectionResult.Failure(e.toString())
    }

    /**
     * Runs the blur -> Sobel -> multi-threshold -> dilate -> quad pipeline on an already-grayscale buffer. Pure; shared by one-shot detection
     * ([detectFromRgba]) and live scanning.
     *
     * [previousQuad], when supplied, nudges [Contours.pickBestQuad] toward whichever candidate best corresponds to it; null for one-shot detection.
     */
    fun detectQuadFromGrayscale(gray: ByteArray, width: Int, height: Int, previousQuad: Quad? = null): Quad? {
        val blurred = EdgeDetection.gaussianBlur3(gray, width, height)
        val magnitude = EdgeDetection.sobelMagnitude(blurred, width, height)
        val baseThreshold = EdgeDetection.otsuThreshold(magnitude)

        val candidates = ArrayList<Quad>()
        for (multiplier in THRESHOLD_MULTIPLIERS) {
            val t = dartRound(baseThreshold * multiplier).coerceIn(0, 255)
            val binary = EdgeDetection.threshold(magnitude, t)
            val dilated = EdgeDetection.dilate(binary, width, height, 4)
            candidates += Contours.findDocumentQuadCandidates(dilated, width, height)
        }

        return Contours.pickBestQuad(candidates, width, height, previousQuad)
    }

    /** Nearest-neighbour downsample of an RGBA buffer (stride 4). Returns [src] itself when the size is unchanged. */
    internal fun downscaleRgba(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        if (srcW == dstW && srcH == dstH) return src

        val dst = ByteArray(dstW * dstH * 4)
        val sxs = IntArray(dstW) { x -> floor((x.toLong() * srcW).toDouble() / dstW).toInt().coerceIn(0, srcW - 1) }
        for (y in 0 until dstH) {
            val sy = floor((y.toLong() * srcH).toDouble() / dstH).toInt().coerceIn(0, srcH - 1)
            for (x in 0 until dstW) {
                val sx = sxs[x]
                val srcIdx = (sy * srcW + sx) * 4
                val dstIdx = (y * dstW + x) * 4
                dst[dstIdx] = src[srcIdx]
                dst[dstIdx + 1] = src[srcIdx + 1]
                dst[dstIdx + 2] = src[srcIdx + 2]
                dst[dstIdx + 3] = src[srcIdx + 3]
            }
        }
        return dst
    }
}
