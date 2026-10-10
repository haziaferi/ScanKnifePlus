// Ported from OpenScan lib/core/cv/frame_adapter.dart and the low-light check in lib/view/screens/live_scan/live_scan_screen.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.live

import com.haziaferi.scanknifeplus.scanner.dartFloor
import com.haziaferi.scanknifeplus.scanner.dartRound
import com.haziaferi.scanknifeplus.scanner.setU8
import com.haziaferi.scanknifeplus.scanner.u8

/**
 * Turns a live camera frame into the small grayscale buffer that live detection runs on, downsampling in the same pass so the full-resolution
 * frame is never materialized as grayscale. Only OpenScan's YUV420 path is ported: its BGRA8888 path is iOS-only and CameraX never delivers it.
 */
object FrameAdapter {
    /** Longest edge (px) live detection runs at; the overlay is guidance only, and the captured photo is detected again at full resolution. */
    const val LIVE_DETECTION_MAX_DIMENSION = 320

    /**
     * Size of the downsampled frame: scaled so the longest edge is [targetLongEdge], never upscaled; null for an empty frame. OpenScan's screen
     * recomputes this without the clamp to 1; the two differ only above a 640:1 aspect ratio, which cameras do not deliver.
     */
    fun downsampledSize(width: Int, height: Int, targetLongEdge: Int = LIVE_DETECTION_MAX_DIMENSION): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val scale = targetLongEdge.toDouble() / maxOf(width, height)
        if (scale >= 1.0) return width to height
        return dartRound(width * scale).coerceIn(1, width) to dartRound(height * scale).coerceIn(1, height)
    }

    /**
     * Downsamples the Y (luma) plane of a YUV420 frame, which is already grayscale. Each output pixel is the rounded mean of the 3x3 neighbourhood
     * around its nearest source pixel, which keeps per-pixel sensor noise from aliasing into detection. [bytesPerRow] may exceed [width] because of
     * row padding, so rows are always indexed by stride. The Y plane's pixel stride is always 1 in YUV_420_888. Returns null for an empty frame.
     */
    fun grayscaleFromYPlane(
        yPlane: ByteArray,
        bytesPerRow: Int,
        width: Int,
        height: Int,
        targetLongEdge: Int = LIVE_DETECTION_MAX_DIMENSION,
    ): ByteArray? {
        val (dstW, dstH) = downsampledSize(width, height, targetLongEdge) ?: return null
        val dst = ByteArray(dstW * dstH)
        // The three clamped source columns around each output column's nearest pixel.
        val columns = IntArray(dstW * 3)
        for (x in 0 until dstW) {
            val sx0 = dartFloor(x.toDouble() * width / dstW).coerceIn(0, width - 1)
            for (dx in -1..1) {
                columns[x * 3 + dx + 1] = (sx0 + dx).coerceIn(0, width - 1)
            }
        }
        for (y in 0 until dstH) {
            val sy0 = dartFloor(y.toDouble() * height / dstH).coerceIn(0, height - 1)
            for (x in 0 until dstW) {
                var sum = 0
                for (dy in -1..1) {
                    val rowOffset = (sy0 + dy).coerceIn(0, height - 1) * bytesPerRow
                    for (c in x * 3 until x * 3 + 3) {
                        sum += yPlane.u8(rowOffset + columns[c])
                    }
                }
                dst.setU8(y * dstW + x, dartRound(sum / 9.0))
            }
        }
        return dst
    }
}

/**
 * Tells a dim room from a lit one from the downsampled live frame, with a dead band so the hint doesn't flicker while auto-exposure hunts around
 * the threshold: it turns on below [THRESHOLD] and off again only at [THRESHOLD] + [HYSTERESIS] or above. Not thread-safe.
 */
class LowLightDetector {
    companion object {
        const val THRESHOLD = 55
        const val HYSTERESIS = 8
        private const val SAMPLE_STEP = 16
    }

    var isLowLight = false
        private set

    /** Updates from a grayscale frame (every 16th sample is averaged) and returns whether the state changed; an empty frame changes nothing. */
    fun update(gray: ByteArray): Boolean {
        if (gray.isEmpty()) return false
        var sum = 0L
        var count = 0
        var i = 0
        while (i < gray.size) {
            sum += gray.u8(i)
            count++
            i += SAMPLE_STEP
        }
        val mean = sum.toDouble() / count
        val lowLight = if (isLowLight) mean < THRESHOLD + HYSTERESIS else mean < THRESHOLD
        if (lowLight == isLowLight) return false
        isLowLight = lowLight
        return true
    }
}
