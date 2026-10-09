// Ported from OpenScan lib/view/screens/live_scan/live_scan_controller.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.live

import com.haziaferi.scanknifeplus.scanner.cv.Contours
import com.haziaferi.scanknifeplus.scanner.cv.DocumentDetector
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The per-frame detection state of a live-scan session (what OpenScan's worker isolate keeps): runs [DocumentDetector.detectQuadFromGrayscale]
 * and feeds the last detected quad back in as `previousQuad`, so candidate selection is biased toward what was last seen. The hint is dropped after
 * [FORGET_PREVIOUS_AFTER_MISSES] frames in a row with nothing detected, so a document that has gone away doesn't keep pulling later detections
 * toward where it was. Not thread-safe: use from one thread at a time.
 */
class LiveDetectionWorker {
    companion object {
        const val FORGET_PREVIOUS_AFTER_MISSES = 5
    }

    private var previousQuad: Quad? = null
    private var missStreak = 0

    /** Detects the quad in a sensor-native grayscale frame; the result is in the frame's own pixel coordinates. */
    fun process(gray: ByteArray, width: Int, height: Int): Quad? {
        val quad = DocumentDetector.detectQuadFromGrayscale(gray, width, height, previousQuad)
        if (quad != null) {
            previousQuad = quad
            missStreak = 0
        } else if (++missStreak >= FORGET_PREVIOUS_AFTER_MISSES) {
            previousQuad = null
        }
        return quad
    }
}

/**
 * Runs live detection on one background thread for the lifetime of a live-scan session. Frames are submitted already grayscale and downsampled,
 * in sensor-native (landscape) coordinates; results are published rotated into portrait overlay space and normalized to [0,1]
 * ([rotateQuadForPortrait]).
 *
 * Only one frame is ever in flight: [submitFrame] drops frames while a previous one is still being processed, so work never backs up.
 *
 * Results are published like OpenScan's two `ValueNotifier`s. [onQuad] fires when [latestQuad] changes: every detected quad is a new instance so it
 * always fires, but a miss after a miss does not fire again. [onLatency] fires when [lastLatencyMs] changes value. Both run on the background
 * thread, so the caller hops to its UI thread if needed. The controller owns [executor] and shuts it down in [dispose].
 */
class LiveScanController(
    private val onQuad: (Quad?) -> Unit,
    private val onLatency: (Long) -> Unit = {},
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "live-scan-detection").apply { isDaemon = true } },
) {
    private val worker = LiveDetectionWorker()
    private val detecting = AtomicBoolean(false)
    private val nextRequestId = AtomicInteger(0)

    @Volatile
    private var disposed = false

    // Only touched on the executor thread.
    private var lastHandledRequestId = -1

    /** Latest detected quad, normalized to portrait [0,1] space; null if nothing was detected in the most recent processed frame. */
    @Volatile
    var latestQuad: Quad? = null
        private set

    /** Round-trip latency (submit to result) of the most recently completed detection, in milliseconds. */
    @Volatile
    var lastLatencyMs: Long? = null
        private set

    val isBusy: Boolean get() = detecting.get()

    /**
     * Submits a downsampled grayscale frame; returns false (frame dropped) if a previous frame is still being processed or after [dispose].
     * The frame is copied, as Dart's `SendPort.send` copies it, so the caller may reuse its buffer as soon as this returns.
     */
    fun submitFrame(gray: ByteArray, width: Int, height: Int): Boolean {
        if (disposed || !detecting.compareAndSet(false, true)) return false
        val frame = gray.copyOf()
        val requestId = nextRequestId.getAndIncrement()
        val startedAt = System.nanoTime()
        try {
            executor.execute { handleFrame(frame, width, height, requestId, startedAt) }
        } catch (e: RejectedExecutionException) {
            // dispose() shut the executor down between the check above and here.
            detecting.set(false)
            return false
        }
        return true
    }

    private fun handleFrame(frame: ByteArray, width: Int, height: Int, requestId: Int, startedAt: Long) {
        var quad: Quad? = null
        try {
            quad = worker.process(frame, width, height)
        } catch (e: Exception) {
            // A failed detection counts as "nothing found" so the session keeps running; OpenScan's isolate would stop answering instead.
        } finally {
            detecting.set(false)
        }
        if (disposed) return

        val latencyMs = (System.nanoTime() - startedAt) / 1_000_000
        if (latencyMs != lastLatencyMs) {
            lastLatencyMs = latencyMs
            onLatency(latencyMs)
        }
        // Kept from OpenScan; with one frame in flight on one thread a stale result cannot actually arrive.
        if (requestId < lastHandledRequestId) return
        lastHandledRequestId = requestId

        val published = quad?.let { rotateQuadForPortrait(it, width, height) }
        if (published == null && latestQuad == null) return
        latestQuad = published
        onQuad(published)
    }

    /** Stops detection; no result is published once this returns, except one whose callback had already started. */
    fun dispose() {
        disposed = true
        executor.shutdownNow()
    }
}

/**
 * Rotates a quad detected on a sensor-native (landscape, w > h) frame into portrait overlay space, matching a back camera's fixed 90-degree sensor
 * mount, and normalizes it to [0,1] so callers can map it onto any preview box by multiplying by the box's size.
 *
 * Rotation doesn't preserve which corner is visually top-left, so the canonical order is re-derived with [Contours.sortCorners].
 */
fun rotateQuadForPortrait(quad: Quad, frameWidth: Int, frameHeight: Int): Quad {
    val rotated = Contours.sortCorners(quad.points.map { Pt(frameHeight - it.y, it.x) })
    return rotated.scaled(1.0 / frameHeight, 1.0 / frameWidth)
}
