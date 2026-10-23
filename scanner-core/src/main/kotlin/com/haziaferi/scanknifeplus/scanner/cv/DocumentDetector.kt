// Ported from OpenScan lib/core/cv/document_detector.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
// The first candidate family is ported from OSS-DocumentScanner (https://github.com/ossappscollective/OSS-DocumentScanner) at commit
// ff05fc52b601d1cccb4076f3f6c3318f74e72f0c: cpp/src/DocumentDetector.cpp scanPoint as the app's one-shot path calls it.
// MIT License, Copyright (c) 2022 Martin Guillon. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.cv

import com.haziaferi.scanknifeplus.scanner.dartRound
import kotlin.math.floor
import kotlin.math.max

/**
 * Finds the document's corners in a photo or live frame. OSS-DocumentScanner's candidates come first (brightness and Canny masks per channel
 * at [OUTLINE_MAX_DIMENSION] with a black border, so a page cut off by the frame still closes); when they find nothing, OpenScan's
 * (Sobel masks at several thresholds, pooled and ranked), which always return a quad where the old detector did.
 */
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

    /** Longest edge (px) OSS-DocumentScanner's candidates are searched at: their one-shot `resizeThreshold`, before the border. */
    const val OUTLINE_MAX_DIMENSION = 300

    /**
     * Detects the document in a decoded RGBA image (stride 4) of [width] x [height]. Exceptions come back as [DetectionResult.Failure].
     *
     * This is the decode-independent part of OpenScan's `detectDocumentIsolateEntry`; the caller decodes the image file.
     */
    fun detectFromRgba(rgba: ByteArray, width: Int, height: Int): DetectionResult = try {
        val (workWidth, workHeight) = fitted(width, height, DETECTION_MAX_DIMENSION)
        val small = downscaleRgba(rgba, width, height, workWidth, workHeight)
        val gray = EdgeDetection.rgbaToGrayscale(small, workWidth, workHeight)
        val (pw, ph) = fitted(width, height, OUTLINE_MAX_DIMENSION)
        // B, G, R: the order OSS-DocumentScanner's loop visits an Android RGBA bitmap's channels in.
        val planes = EdgeMasks.areaResample(rgba, width, height, 4, intArrayOf(2, 1, 0), pw, ph)
        val quad = detect(gray, workWidth, workHeight, planes, pw, ph, null)
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
     * Detects the document in a grayscale frame (live scanning) with the same detector as [detectFromRgba], on the one luminance channel the
     * frame has. The quad is in the frame's pixel coordinates.
     *
     * [previousQuad], when supplied, nudges OpenScan's ranking ([Contours.pickBestQuad]) toward whichever candidate best corresponds to it.
     */
    fun detectQuadFromGrayscale(gray: ByteArray, width: Int, height: Int, previousQuad: Quad? = null): Quad? {
        val (pw, ph) = fitted(width, height, OUTLINE_MAX_DIMENSION)
        val planes = if (pw == width && ph == height) listOf(gray) else EdgeMasks.areaResample(gray, width, height, 1, intArrayOf(0), pw, ph)
        return detect(gray, width, height, planes, pw, ph, previousQuad)
    }

    /**
     * OSS-DocumentScanner's best square from [planes] ([pw] x [ph]) when there is one, else OpenScan's candidates from [gray] ranked by
     * [Contours.pickBestQuad]. The quad is in [gray]'s coordinates.
     */
    private fun detect(gray: ByteArray, width: Int, height: Int, planes: List<ByteArray>, pw: Int, ph: Int, previousQuad: Quad?): Quad? {
        val bw = pw + 2 * SquareFinder.BORDER
        val bh = ph + 2 * SquareFinder.BORDER
        val prepared = planes.map { EdgeMasks.medianBlur3(EdgeMasks.addBorder(it, pw, ph, SquareFinder.BORDER), bw, bh) }
        val square = SquareFinder.findBest(prepared, bw, bh)
        if (square != null) return square.toQuad(SquareFinder.BORDER, width.toDouble() / pw, height.toDouble() / ph)
        return Contours.pickBestQuad(edgeCandidates(gray, width, height), width, height, previousQuad)
    }

    /** OpenScan's candidates: blur, Sobel, three thresholds around Otsu's, dilate, quads from the largest components. */
    private fun edgeCandidates(gray: ByteArray, width: Int, height: Int): List<Quad> {
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
        return candidates
    }

    /** [width] x [height] scaled so the longest edge is at most [maxDimension], never upscaled. */
    private fun fitted(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        val longestEdge = max(width, height).toDouble()
        val scale = if (longestEdge > maxDimension) maxDimension / longestEdge else 1.0
        return max(1, dartRound(width * scale)) to max(1, dartRound(height * scale))
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
