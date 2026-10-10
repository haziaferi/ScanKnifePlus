// Ported from OpenScan lib/view/screens/live_scan/auto_capture_detector.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.live

import com.haziaferi.scanknifeplus.scanner.cv.Quad
import kotlin.math.abs

/**
 * Decides when the document has been held still long enough to auto-capture, checking only positional stability since quads arrive
 * plausibility-filtered. [onStable] fires when it has; the caller calls [notifyCaptured] after any capture (auto or manual) to start the
 * cooldown. [onImminentChanged] reports whether an auto-capture is about to happen, for a visual cue.
 */
class AutoCaptureDetector(
    private val onStable: () -> Unit,
    private val onImminentChanged: ((Boolean) -> Unit)? = null,
    private val clock: MicrosClock = MicrosClock.SYSTEM,
) {
    companion object {
        /** Consecutive stable detections required; low enough not to be the binding constraint ([MIN_STABLE_DURATION_MICROS] is the real knob). */
        const val STABLE_FRAME_COUNT = 3

        /** Max per-corner movement between consecutive frames, as a fraction of the normalized space, for a frame to count as still. */
        const val POSITION_TOLERANCE_FRACTION = 0.02

        /** Minimum wall-clock span the stable window must cover, so a burst of quick results can't satisfy the frame count alone. */
        const val MIN_STABLE_DURATION_MICROS = 700_000L

        /** Cooldown after any capture before auto-capture can fire again. */
        const val COOLDOWN_DURATION_MICROS = 2_000_000L
    }

    private val window = ArrayList<Quad>()
    private var windowStart: Long? = null
    private var cooldownUntil: Long? = null
    private var imminent = false

    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) resetState()
        }

    val isInCooldown: Boolean
        get() = cooldownUntil.let { it != null && clock.nowMicros() < it }

    /** Feed each new detection result; pass null for a frame where nothing was detected. */
    fun onQuadUpdate(quad: Quad?) {
        if (!enabled || isInCooldown || quad == null) {
            resetState()
            return
        }

        if (window.isEmpty() || !isWithinTolerance(window.last(), quad)) {
            window.clear()
            windowStart = clock.nowMicros()
        }
        window += quad

        setImminent(window.size >= STABLE_FRAME_COUNT - 2)

        if (window.size >= STABLE_FRAME_COUNT && clock.nowMicros() - windowStart!! >= MIN_STABLE_DURATION_MICROS) {
            onStable()
        }
    }

    /** Starts the post-capture cooldown and clears the stability window. */
    fun notifyCaptured() {
        cooldownUntil = clock.nowMicros() + COOLDOWN_DURATION_MICROS
        resetState()
    }

    private fun resetState() {
        window.clear()
        windowStart = null
        setImminent(false)
    }

    private fun setImminent(value: Boolean) {
        if (imminent == value) return
        imminent = value
        onImminentChanged?.invoke(value)
    }

    private fun isWithinTolerance(a: Quad, b: Quad): Boolean {
        val pa = a.points
        val pb = b.points
        for (i in 0 until 4) {
            val dx = abs(pa[i].x - pb[i].x)
            val dy = abs(pa[i].y - pb[i].y)
            if (dx > POSITION_TOLERANCE_FRACTION || dy > POSITION_TOLERANCE_FRACTION) return false
        }
        return true
    }
}
