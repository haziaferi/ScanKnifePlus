// Ported from OpenScan lib/view/screens/live_scan/live_scan_screen.dart (_requestZoomLevel, _issuePendingZoom, _onZoomSliderChangeEnd).
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.live

/** Runs an action once after a delay, on the thread the throttle is used from. Injected so tests can drive time by hand. */
fun interface DelayScheduler {
    /** Schedules [action] to run once after [delayMicros]; the returned handle cancels it if it has not run yet. */
    fun schedule(delayMicros: Long, action: () -> Unit): Cancellable
}

/** A scheduled action that can still be called off. */
fun interface Cancellable {
    fun cancel()
}

/**
 * Coalesces zoom-slider updates into camera zoom calls: at most one call per [INTERVAL_MICROS], always carrying the latest target rather than
 * replaying every value of a drag. Calls are issued without waiting for the previous one to finish. OpenScan measured a single zoom call taking
 * ~370 ms to resolve (it completes only once the zoom reaches a capture request), so chaining calls capped the preview at ~3 zoom steps a second;
 * superseding an in-flight call is fine, the camera drops the older request and applies the newer one.
 *
 * [apply] issues the actual zoom call; it must not block. Not thread-safe: use it, and let [scheduler] run its actions, on one thread.
 */
class ZoomThrottle(
    private val apply: (Float) -> Unit,
    private val scheduler: DelayScheduler,
    private val clock: MicrosClock = MicrosClock.SYSTEM,
) {
    companion object {
        /** Minimum spacing between two zoom calls (OpenScan's `_kZoomCallInterval`, 60 ms). */
        const val INTERVAL_MICROS = 60_000L
    }

    private var pendingTarget: Float? = null
    private var lastCallAt: Long? = null
    private var timer: Cancellable? = null

    /**
     * True from the first [request] of a gesture until [finish] or [cancel]. Live detection pauses while this is set, as in OpenScan: its result
     * is meaningless while the framing changes, and skipping it frees the CPU for the gesture.
     */
    var isZooming: Boolean = false
        private set

    /** The latest target not yet handed to [apply], if any. */
    val pending: Float? get() = pendingTarget

    /** Asks for [target]: issued now if the last call was at least [INTERVAL_MICROS] ago, otherwise by a timer at the end of the interval. */
    fun request(target: Float) {
        isZooming = true
        pendingTarget = target
        val now = clock.nowMicros()
        val sinceLast = lastCallAt?.let { now - it } ?: Long.MAX_VALUE
        if (sinceLast >= INTERVAL_MICROS) {
            issuePending()
            return
        }
        // Too soon: an already scheduled flush picks up this newer target, or one is scheduled for the rest of the interval.
        if (timer == null) {
            timer = scheduler.schedule(INTERVAL_MICROS - sinceLast) {
                timer = null
                issuePending()
            }
        }
    }

    /**
     * Ends the gesture. The throttle may have held back the last update of the drag, so it is issued at once, leaving the camera exactly where the
     * slider was released rather than one interval behind it.
     */
    fun finish() {
        isZooming = false
        if (pendingTarget != null) {
            timer?.cancel()
            timer = null
            issuePending()
        }
    }

    /** Drops any pending target and timer without issuing it (the camera went away). */
    fun cancel() {
        isZooming = false
        pendingTarget = null
        timer?.cancel()
        timer = null
    }

    private fun issuePending() {
        val target = pendingTarget ?: return
        pendingTarget = null
        lastCallAt = clock.nowMicros()
        apply(target)
    }
}
