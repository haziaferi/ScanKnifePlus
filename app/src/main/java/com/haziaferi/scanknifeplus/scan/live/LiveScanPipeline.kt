// Ported from OpenScan lib/view/screens/live_scan/live_scan_screen.dart (the frame and detection glue, not the screen).
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scan.live

import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.live.AutoCaptureDetector
import com.haziaferi.scanknifeplus.scanner.live.FrameAdapter
import com.haziaferi.scanknifeplus.scanner.live.LiveScanController
import com.haziaferi.scanknifeplus.scanner.live.LowLightDetector
import com.haziaferi.scanknifeplus.scanner.live.MicrosClock
import com.haziaferi.scanknifeplus.scanner.live.QuadSmoother
import java.nio.Buffer
import java.nio.ByteBuffer

/** A capture the pipeline has started: [quad] is the boundary on screen as the shutter fires (portrait, [0,1]), or null to keep the whole photo. */
class CaptureRequest(val quad: Quad?)

/**
 * The non-visual part of OpenScan's live-scan screen: takes every camera analysis frame, runs live document detection on every third one, smooths
 * the detections, and decides when to auto-capture. It knows nothing about the camera library; the camera side calls [onFrame] for each frame and
 * the capture methods around each still.
 *
 * Flow, as in OpenScan: frame -> [FrameAdapter] grayscale (320 px) -> [LowLightDetector] and [LiveScanController] -> raw quad -> [QuadSmoother]
 * -> smoothed quad -> [AutoCaptureDetector] -> [Listener.onAutoCapture]. The smoother and the auto-capture detector are event driven and only read
 * [clock] (no animation ticker), so they advance whenever a detection result arrives.
 *
 * Capture protocol: call [beginCapture] for a manual shutter (auto-capture calls it itself and hands the request to [Listener.onAutoCapture]), then
 * [notifyCaptured] once the still has been taken, then [endCapture] when the page is done or the capture failed. Frames are ignored from
 * [beginCapture] until [notifyCaptured] or [endCapture], as OpenScan stops its image stream for the shutter.
 *
 * Threading: [onFrame] is called on the camera's analysis thread; the other methods may be called from any thread. Every [Listener] callback runs
 * while the pipeline holds its internal lock, so callbacks arrive in order and never after [dispose] has returned. A callback must not block or
 * wait on another thread that calls into this pipeline; post to the main thread instead. Calling back into the pipeline from the same thread is
 * allowed but [Listener.onAutoCapture] should hand the capture off rather than take it synchronously.
 */
class LiveScanPipeline internal constructor(
    private val listener: Listener,
    clock: MicrosClock,
    detectorFactory: (onQuad: (Quad?) -> Unit) -> QuadDetector,
) {
    /** Creates a pipeline that detects on its own background thread (owned by the pipeline and stopped in [dispose]). */
    constructor(listener: Listener, clock: MicrosClock = MicrosClock.SYSTEM) : this(listener, clock, { onQuad ->
        LiveScanController(onQuad).asQuadDetector()
    })

    /** Receives the pipeline's state for the UI. All methods have empty defaults except [onAutoCapture]. See the class doc for threading. */
    interface Listener {
        /**
         * The smoothed document boundary changed (portrait, normalized to [0,1]; null when nothing is tracked). Runs on the detection thread, or on
         * the thread that called [notifyCaptured] (which publishes null).
         */
        fun onSmoothedQuadChanged(quad: Quad?) {}

        /** The scene became too dark for reliable detection, or bright enough again. Runs on the analysis thread. */
        fun onLowLightChanged(lowLight: Boolean) {}

        /**
         * An auto-capture is about to happen (for a visual cue), or no longer is. Runs on the detection thread, or on the thread that called
         * [setAutoCaptureEnabled] or [notifyCaptured].
         */
        fun onAutoCaptureImminentChanged(imminent: Boolean) {}

        /**
         * The document has been held still long enough: take the still now. The capture is already begun (as by [beginCapture]), so the caller
         * must follow with [notifyCaptured] and/or [endCapture]. Runs on the detection thread.
         */
        fun onAutoCapture(request: CaptureRequest)
    }

    private val lock = Any()
    private val smoother = QuadSmoother(clock)
    private val autoCapture = AutoCaptureDetector(onStable = ::onAutoCaptureStable, onImminentChanged = ::onImminentChanged, clock = clock)
    private val lowLightDetector = LowLightDetector()
    private val detector: QuadDetector

    // Analysis thread only. Only kept frames (at most one in three) are copied: about 1 MB at the 1280x720 analysis size, well under a millisecond,
    // and it lets FrameAdapter stay the parity-tested ByteArray port.
    private var frameCounter = 0
    private var frameCopy = ByteArray(0)

    // Written under lock; volatile so onFrame can read them without taking it.
    @Volatile private var disposed = false
    @Volatile private var zooming = false
    @Volatile private var lensFacingBack = true
    @Volatile private var capturing = false
    @Volatile private var shutterOpen = false

    /** The current smoothed quad (portrait, [0,1]), or null; the value last passed to [Listener.onSmoothedQuadChanged]. */
    @Volatile
    var smoothedQuad: Quad? = null
        private set

    /** Whether the scene is currently too dark; the value last passed to [Listener.onLowLightChanged]. */
    @Volatile
    var isLowLight = false
        private set

    /** Whether an auto-capture is imminent; the value last passed to [Listener.onAutoCaptureImminentChanged]. */
    @Volatile
    var isAutoCaptureImminent = false
        private set

    /** Whether a capture is in progress, from [beginCapture] (or an auto-capture) until [endCapture]. */
    val isCapturing: Boolean get() = capturing

    init {
        smoother.onChanged = ::onSmoothedQuadChanged
        detector = detectorFactory(::onRawQuad)
    }

    /**
     * Handles one camera frame: the Y plane of a YUV_420_888 image in sensor-native orientation, pixel stride 1, rows [rowStride] bytes apart (the
     * last row may be unpadded). Read from the buffer's position, which is left unchanged; [yPlane] is only used during the call. Only every third
     * frame is analysed, and none while detection is busy, while zooming, or while the shutter is open. Call on the analysis thread.
     */
    fun onFrame(yPlane: ByteBuffer, rowStride: Int, width: Int, height: Int) {
        if (disposed || shutterOpen) return
        if (++frameCounter % 3 != 0) return
        if (detector.isBusy) return
        // Detection is meaningless mid-pinch (the framing is changing under it), and skipping it frees the CPU for the zoom.
        if (zooming) return
        if (width <= 0 || height <= 0 || rowStride < width) return

        val needed = rowStride.toLong() * (height - 1) + width
        if (needed > yPlane.remaining()) return
        if (frameCopy.size < needed) frameCopy = ByteArray(needed.toInt())
        val position = yPlane.position()
        yPlane.get(frameCopy, 0, needed.toInt())
        // Through Buffer: ByteBuffer's covariant position(Int) is newer than minSdk.
        (yPlane as Buffer).position(position)

        val gray = FrameAdapter.grayscaleFromYPlane(frameCopy, rowStride, width, height) ?: return
        if (lowLightDetector.update(gray)) {
            synchronized(lock) {
                if (disposed) return
                isLowLight = lowLightDetector.isLowLight
                listener.onLowLightChanged(isLowLight)
            }
        }
        val (w, h) = FrameAdapter.downsampledSize(width, height) ?: return
        detector.submitFrame(gray, w, h)
    }

    /** Pauses detection and auto-capture while the user is zooming, as OpenScan does during a zoom drag. */
    fun setZooming(zooming: Boolean) {
        this.zooming = zooming
    }

    /** Which camera the frames come from. Only a back camera's quad maps onto its still, so a front-camera capture keeps the whole photo. */
    fun setLensFacingBack(back: Boolean) {
        lensFacingBack = back
    }

    /** Turns auto-capture on or off (on by default). Persisting the choice is the caller's job. */
    fun setAutoCaptureEnabled(enabled: Boolean) = synchronized(lock) {
        if (disposed) return
        autoCapture.enabled = enabled
    }

    /**
     * Starts a capture and snapshots the boundary on screen: the smoothed quad for the back camera, null for the front one. Returns null, and does
     * nothing, if a capture is already in progress or the pipeline is disposed.
     */
    fun beginCapture(): CaptureRequest? = synchronized(lock) {
        if (disposed || capturing) return null
        capturing = true
        shutterOpen = true
        CaptureRequest(if (lensFacingBack) smoother.smoothedQuad else null)
    }

    /** The still has been taken: starts the auto-capture cooldown, drops the smoothed track so the next document starts fresh, resumes frames. */
    fun notifyCaptured() = synchronized(lock) {
        if (disposed) return
        autoCapture.notifyCaptured()
        smoother.reset()
        shutterOpen = false
    }

    /**
     * The capture is over (its page is processed, or it failed): manual and auto-capture are allowed again and frames resume. A capture that ended
     * without [notifyCaptured] (the still failed) still starts the auto-capture cooldown. OpenScan left its stream stopped after a failure; with
     * frames resuming, an unchanged scene would otherwise trigger the next auto-capture at once, and a camera that keeps failing would loop.
     */
    fun endCapture() = synchronized(lock) {
        if (capturing && shutterOpen && !disposed) autoCapture.notifyCaptured()
        capturing = false
        shutterOpen = false
    }

    /** Stops detection. Once this returns no listener callback runs and every other method does nothing. */
    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
        }
        detector.dispose()
    }

    private fun onRawQuad(quad: Quad?) = synchronized(lock) {
        if (disposed) return
        smoother.onRawQuad(quad)
    }

    // Called by the smoother, under lock.
    // The disposed checks cover a listener that calls dispose() from inside a callback on the same thread (the lock is reentrant).
    private fun onSmoothedQuadChanged(quad: Quad?) {
        if (disposed) return
        smoothedQuad = quad
        listener.onSmoothedQuadChanged(quad)
        if (zooming || disposed) return
        autoCapture.onQuadUpdate(quad)
    }

    // Called by the auto-capture detector, under lock.
    private fun onImminentChanged(imminent: Boolean) {
        if (disposed) return
        isAutoCaptureImminent = imminent
        listener.onAutoCaptureImminentChanged(imminent)
    }

    // Called by the auto-capture detector, under lock.
    private fun onAutoCaptureStable() {
        val request = beginCapture() ?: return
        listener.onAutoCapture(request)
    }
}

/** The detection stage the pipeline drives; [LiveScanController] in production, a fake in tests. */
internal interface QuadDetector {
    val isBusy: Boolean

    fun submitFrame(gray: ByteArray, width: Int, height: Int): Boolean

    fun dispose()
}

internal fun LiveScanController.asQuadDetector(): QuadDetector = object : QuadDetector {
    override val isBusy: Boolean get() = this@asQuadDetector.isBusy

    override fun submitFrame(gray: ByteArray, width: Int, height: Int) = this@asQuadDetector.submitFrame(gray, width, height)

    override fun dispose() = this@asQuadDetector.dispose()
}
