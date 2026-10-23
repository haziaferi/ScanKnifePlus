// Ported from OpenScan lib/view/screens/live_scan/auto_capture_detector.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.live

import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.cv.quadOf
import com.haziaferi.scanknifeplus.scanner.cv.toScalars
import kotlin.math.hypot
import kotlin.math.max

/**
 * Decides when the document has been held still long enough to auto-capture, checking only positional stability since quads arrive
 * plausibility-filtered. [onStable] fires when it has; the caller calls [notifyCaptured] after any capture (auto or manual) to start the
 * cooldown. [onImminentChanged] reports whether an auto-capture is about to happen, for a visual cue.
 *
 * Unlike OpenScan, which compared consecutive quads, stillness has two tests (a user-approved departure): each quad must lie near the mean of
 * the last [REFERENCE_WINDOW_MICROS], which absorbs hand shake but restarts the hold on a jump, and over the hold the outline's mean position
 * must not travel more than [DRIFT_TOLERANCE_FRACTION], which rejects a page that is still sliding. All distances are in the normalized
 * [0,1] space and all timing is on [clock], so neither the frame size nor the detection rate changes the outcome.
 */
class AutoCaptureDetector(
    private val onStable: () -> Unit,
    private val onImminentChanged: ((Boolean) -> Unit)? = null,
    private val clock: MicrosClock = MicrosClock.SYSTEM,
) {
    companion object {
        /** Stable detections required; low enough not to be the binding constraint ([MIN_STABLE_DURATION_MICROS] is the real knob). */
        const val STABLE_FRAME_COUNT = 3

        /** How far back the reference each new quad is compared with reaches: over one and a half cycles of 8 Hz hand tremor. */
        const val REFERENCE_WINDOW_MICROS = 200_000L

        /**
         * Max distance of any corner from the reference (the mean of the last [REFERENCE_WINDOW_MICROS]), as a fraction of the normalized space,
         * before the hold restarts. Covers 2% of hand shake on top of detector noise.
         */
        const val JITTER_TOLERANCE_FRACTION = 0.04

        /**
         * Max distance any corner's mean over the first [REFERENCE_WINDOW_MICROS] of the hold may lie from its mean over the last, as a fraction of
         * the normalized space. Over a [MIN_STABLE_DURATION_MICROS] hold this lets an outline creep by up to about 2% of the frame per second.
         */
        const val DRIFT_TOLERANCE_FRACTION = 0.01

        /** Minimum wall-clock span the stable window must cover, so a burst of quick results can't satisfy the frame count alone. */
        const val MIN_STABLE_DURATION_MICROS = 700_000L

        /** Cooldown after any capture before auto-capture can fire again. */
        const val COOLDOWN_DURATION_MICROS = 2_000_000L
    }

    private class Sample(val at: Long, val quad: Quad)

    // The current hold, oldest first; trimmed to the last MIN_STABLE_DURATION_MICROS once it is longer.
    private val window = ArrayDeque<Sample>()
    private var cooldownUntil: Long? = null
    private var imminent = false

    var enabled: Boolean = true
        set(value) {
            field = value
            if (!value) resetState()
        }

    val isInCooldown: Boolean
        get() = cooldownUntil.let { it != null && clock.nowMicros() < it }

    /** How many quads the current hold has: 0 when nothing is tracked, while disabled or in the cooldown, and 1 right after the hold restarts. */
    val stillQuadCount: Int
        get() = window.size

    /** Feed each new detection result; pass null for a frame where nothing was detected. */
    fun onQuadUpdate(quad: Quad?) {
        if (!enabled || isInCooldown || quad == null) {
            resetState()
            return
        }

        val now = clock.nowMicros()
        if (window.isNotEmpty() && maxCornerDistance(quad, recentMean(now)) > JITTER_TOLERANCE_FRACTION) window.clear()
        window.addLast(Sample(now, quad))
        while (window.size > 1 && now - window[1].at >= MIN_STABLE_DURATION_MICROS) window.removeFirst()
        // Still moving: shorten the hold from its start until what is left has not travelled too far.
        while (window.size > 1 && maxCornerDistance(meanOf { it.at - window.first().at <= REFERENCE_WINDOW_MICROS }, recentMean(now)) >
            DRIFT_TOLERANCE_FRACTION
        ) {
            window.removeFirst()
        }

        setImminent(window.size >= STABLE_FRAME_COUNT - 2)

        if (window.size >= STABLE_FRAME_COUNT && now - window.first().at >= MIN_STABLE_DURATION_MICROS) {
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
        setImminent(false)
    }

    private fun setImminent(value: Boolean) {
        if (imminent == value) return
        imminent = value
        onImminentChanged?.invoke(value)
    }

    /** The mean of the samples in the last [REFERENCE_WINDOW_MICROS], or the newest sample when there are none. */
    private fun recentMean(now: Long): Quad =
        if (now - window.last().at > REFERENCE_WINDOW_MICROS) window.last().quad else meanOf { now - it.at <= REFERENCE_WINDOW_MICROS }

    private inline fun meanOf(include: (Sample) -> Boolean): Quad {
        val sum = DoubleArray(8)
        var count = 0
        for (sample in window) {
            if (!include(sample)) continue
            val scalars = sample.quad.toScalars()
            for (i in 0 until 8) sum[i] += scalars[i]
            count++
        }
        return quadOf(sum, count)
    }

    private fun maxCornerDistance(a: Quad, b: Quad): Double {
        var m = 0.0
        val pa = a.points
        val pb = b.points
        for (i in 0 until 4) m = max(m, hypot(pa[i].x - pb[i].x, pa[i].y - pb[i].y))
        return m
    }
}
