// Ported from OpenScan lib/view/screens/live_scan/live_scan_screen.dart (the frame and detection glue, not the screen).
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scan.live

import com.haziaferi.scanknifeplus.scan.camera.CameraLens
import com.haziaferi.scanknifeplus.scanner.cv.Contours
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.live.AutoCaptureDetector
import com.haziaferi.scanknifeplus.scanner.live.FrameAdapter
import com.haziaferi.scanknifeplus.scanner.live.LiveScanController
import com.haziaferi.scanknifeplus.scanner.live.LowLightDetector
import com.haziaferi.scanknifeplus.scanner.live.MicrosClock
import com.haziaferi.scanknifeplus.scanner.live.QuadSmoother
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A capture the pipeline has started: [quad] is the boundary on screen as the shutter fires (portrait, [0,1]), or null to keep the whole photo. */
class CaptureRequest(val quad: Quad?)

/**
 * The non-visual part of OpenScan's live-scan screen: every third analysis frame -> [FrameAdapter] grayscale -> [LowLightDetector] and
 * [LiveScanController] -> [QuadSmoother] -> [AutoCaptureDetector] -> [Listener.onAutoCapture], with the smoother and auto-capture advancing on
 * [clock] whenever a result arrives. [onFrame] runs on the analysis thread and every other method on any thread.
 */
class LiveScanPipeline internal constructor(
    private val listener: Listener,
    clock: MicrosClock,
    detectorFactory: (onQuad: (Quad?, QuadMapping) -> Unit) -> QuadDetector,
) {
    /** Creates a pipeline that detects on its own background thread (owned by the pipeline and stopped in [dispose]). */
    constructor(listener: Listener, clock: MicrosClock = MicrosClock.SYSTEM) : this(listener, clock, { onQuad -> liveQuadDetector(onQuad) })

    /**
     * Receives the pipeline's state for the UI; all methods but [onAutoCapture] have empty defaults. Callbacks run under the pipeline's lock, so
     * they arrive in order and never after [dispose]; one must not block on another thread that calls into the pipeline.
     */
    interface Listener {
        /**
         * The smoothed document boundary changed (portrait, normalized to [0,1]; null when nothing is tracked). Runs on the detection thread, or
         * with null on the thread that called [notifyCaptured] or the analysis thread when the frames come from another camera.
         */
        fun onSmoothedQuadChanged(quad: Quad?) {}

        /** The scene became too dark for reliable detection, or bright enough again. Runs on the analysis thread. */
        fun onLowLightChanged(lowLight: Boolean) {}

        /**
         * An auto-capture is about to happen (for a visual cue), or no longer is. Runs on the detection thread, on the thread that called
         * [setAutoCaptureEnabled] or [notifyCaptured], or on the analysis thread when the frames come from another camera.
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

    // Analysis thread only. Only kept frames are copied (about 1 MB at 1280x720), so FrameAdapter can stay the parity-tested ByteArray port.
    private var frameCounter = 0
    private var frameCopy = ByteArray(0)

    // Written under lock; volatile so onFrame can read them without taking it.
    @Volatile private var disposed = false
    @Volatile private var zooming = false
    @Volatile private var capturing = false
    @Volatile private var shutterOpen = false

    /** How the latest frames' quads map onto the still; results detected under another mapping are dropped. */
    @Volatile private var frameMapping = QuadMapping.AS_IS

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
     * Handles one frame from [lens]: the Y plane of a YUV_420_888 image in sensor orientation (pixel stride 1, rows [rowStride] bytes apart, read
     * from the buffer's position, which is left unchanged) and the [rotationDegrees] that turn it upright. A back camera at 90 or 270 degrees and
     * the front camera (as in OpenScan) yield quads; every third frame is analysed, none while detection is busy, zooming or the shutter is open.
     */
    fun onFrame(yPlane: ByteBuffer, rowStride: Int, width: Int, height: Int, rotationDegrees: Int, lens: CameraLens) {
        if (disposed || shutterOpen) return
        val mapping = QuadMapping.of(rotationDegrees, lens)
        if (mapping != frameMapping) {
            synchronized(lock) {
                if (disposed) return
                // Another camera: the old track is in another space and must not reach a capture.
                frameMapping = mapping
                smoother.reset()
            }
        }
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
        detector.submitFrame(gray, w, h, mapping)
    }

    /** Pauses detection and auto-capture while the user is zooming, as OpenScan does during a zoom drag. */
    fun setZooming(zooming: Boolean) {
        this.zooming = zooming
    }

    /** Turns auto-capture on or off (on by default). Persisting the choice is the caller's job. */
    fun setAutoCaptureEnabled(enabled: Boolean): Unit = synchronized(lock) {
        if (disposed) return
        autoCapture.enabled = enabled
    }

    /**
     * Starts a capture (auto-capture calls this itself) and snapshots the smoothed quad, null when the frames do not map onto the still (the
     * front camera); follow with [notifyCaptured] once the still is taken and [endCapture] when done, and frames pause until then, as OpenScan
     * stops its stream. Returns null, doing nothing, if a capture is in progress or the pipeline is disposed.
     */
    fun beginCapture(): CaptureRequest? = synchronized(lock) {
        if (disposed || capturing) return null
        capturing = true
        shutterOpen = true
        CaptureRequest(if (frameMapping.mapsOntoStill) smoother.smoothedQuad else null)
    }

    /** The still has been taken: starts the auto-capture cooldown, drops the smoothed track so the next document starts fresh, resumes frames. */
    fun notifyCaptured(): Unit = synchronized(lock) {
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

    private fun onRawQuad(quad: Quad?, mapping: QuadMapping): Unit = synchronized(lock) {
        if (disposed || mapping != frameMapping) return
        smoother.onRawQuad(quad?.let(mapping::map))
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

/**
 * How a frame's detected quad maps onto the still. scanner-core's portrait rotation assumes OpenScan's 90-degree back camera; one mounted at
 * 270 degrees sees the page turned half way round. The front camera keeps OpenScan's mapping for the overlay and auto-capture, but its frames
 * do not match its unmirrored still; other rotations yield no quad.
 */
internal enum class QuadMapping(val mapsOntoStill: Boolean) {
    AS_IS(true),
    HALF_TURN(true),
    FRONT(false),
    NONE(false);

    fun map(quad: Quad): Quad? = when (this) {
        AS_IS, FRONT -> quad
        HALF_TURN -> Contours.sortCorners(quad.points.map { Pt(1 - it.x, 1 - it.y) })
        NONE -> null
    }

    companion object {
        fun of(rotationDegrees: Int, lens: CameraLens): QuadMapping = when {
            lens != CameraLens.BACK -> FRONT
            rotationDegrees == 90 -> AS_IS
            rotationDegrees == 270 -> HALF_TURN
            else -> NONE
        }
    }
}

/** The detection stage the pipeline drives; [liveQuadDetector] in production, a fake in tests. */
internal interface QuadDetector {
    val isBusy: Boolean

    /** Submits a frame; its result, if published, reaches the pipeline together with this frame's [mapping]. */
    fun submitFrame(gray: ByteArray, width: Int, height: Int, mapping: QuadMapping): Boolean

    fun dispose()
}

/** [LiveScanController] on [thread], handing each result to [onQuad] with the mapping of the frame it was detected on. */
internal fun liveQuadDetector(
    onQuad: (Quad?, QuadMapping) -> Unit,
    thread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "live-scan-detection").apply { isDaemon = true } },
): QuadDetector {
    var submitting = QuadMapping.NONE // the submitting thread only
    var current = QuadMapping.NONE // the detection thread only
    // The mapping rides on the detection task, since the controller frees itself before publishing and the next frame may already be submitted.
    val tagging = object : ExecutorService by thread {
        override fun execute(command: Runnable) {
            val mapping = submitting
            thread.execute {
                current = mapping
                command.run()
            }
        }
    }
    val controller = LiveScanController({ onQuad(it, current) }, executor = tagging)
    return object : QuadDetector {
        override val isBusy: Boolean get() = controller.isBusy

        override fun submitFrame(gray: ByteArray, width: Int, height: Int, mapping: QuadMapping): Boolean {
            submitting = mapping
            return controller.submitFrame(gray, width, height)
        }

        override fun dispose() = controller.dispose()
    }
}
