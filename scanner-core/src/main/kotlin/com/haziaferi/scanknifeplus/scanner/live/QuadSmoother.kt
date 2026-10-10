// Ported from OpenScan lib/view/screens/live_scan/quad_smoother.dart.
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner.live

import com.haziaferi.scanknifeplus.scanner.cv.Contours
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.cv.quadOf
import com.haziaferi.scanknifeplus.scanner.cv.toScalars
import kotlin.math.abs
import kotlin.math.max

/**
 * Smooths the noisy per-frame quads into a stable signal for the overlay and [AutoCaptureDetector] without lagging a document that is moving.
 * Positional noise goes through a per-corner One Euro Filter; an outright different detection is held back, compared raw-to-raw rather than
 * against the lagged output, until it has been seen [JUMP_CONFIRM_FRAME_COUNT] times in a row. [onChanged] is called whenever [smoothedQuad] is
 * replaced (by a new instance or null), like the original's `ValueNotifier`.
 */
class QuadSmoother(
    private val clock: MicrosClock = MicrosClock.SYSTEM,
    var onChanged: ((Quad?) -> Unit)? = null,
) {
    companion object {
        /** Baseline low-pass cutoff (Hz) when the signal is stationary; lower means more smoothing at rest. */
        const val MIN_CUTOFF_HZ = 0.5

        /** How much the cutoff rises per unit of corner speed (normalized units/second); higher tracks fast moves more closely. */
        const val BETA = 0.5

        /** Cutoff (Hz) for smoothing the derivative estimate itself (the One Euro Filter's standard two-stage design). */
        const val DERIVATIVE_CUTOFF_HZ = 1.0

        /** How long a missing detection is tolerated before the smoothed quad is cleared, so a momentary miss doesn't flicker the overlay. */
        const val NULL_GRACE_PERIOD_MICROS = 500_000L

        /**
         * Average per-corner distance (fraction of the normalized space's diagonal) between two consecutive raw detections beyond which the newer one
         * is a different document/contour. Compared raw-to-raw, so filter lag during a fast move can never itself look like a jump. Tight on purpose.
         */
        const val JUMP_DISTANCE_FRACTION = 0.06

        /** Consecutive raw samples agreeing with each other that a far-away detection needs before it replaces what's displayed. */
        const val JUMP_CONFIRM_FRAME_COUNT = 3

        /** Diagonal of the normalized [0,1] coordinate space every quad here lives in. */
        private const val NORMALIZED_SPACE_DIAGONAL = 1.4142135623730951 // sqrt(2)
    }

    /** The current smoothed quad, or null when nothing is tracked. */
    var smoothedQuad: Quad? = null
        private set

    // One filter per corner scalar, in [tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y] order. Null whenever there's no active track.
    private var filters: List<OneEuroFilter>? = null
    private var lastSampleAt: Long? = null
    private var lastSeenAt: Long? = null

    // The last raw sample accepted as "the same document": the reference for corner correspondence and the jump check on the next sample.
    private var lastRawQuad: Quad? = null

    // A far-from-lastRawQuad candidate accumulating consecutive matching samples before it may replace the current track.
    private var pendingQuad: Quad? = null
    private var pendingStreak = 0

    /** Feed each new raw detection as it arrives, including nulls (frames where nothing was detected). */
    fun onRawQuad(raw: Quad?) {
        val now = clock.nowMicros()

        if (raw == null) {
            val seen = lastSeenAt
            if (seen != null && now - seen <= NULL_GRACE_PERIOD_MICROS) return
            resetState()
            return
        }

        lastSeenAt = now

        val lastRaw = lastRawQuad
        if (lastRaw == null) {
            pendingQuad = null
            pendingStreak = 0
            lastRawQuad = raw
            seedTrack(raw, now)
            return
        }

        val matchToTrack = Contours.bestCornerAssignment(raw.points, lastRaw)
        val trackDist = asFraction(matchToTrack.totalDistance)
        if (trackDist < JUMP_DISTANCE_FRACTION) {
            pendingQuad = null
            pendingStreak = 0
            lastRawQuad = matchToTrack.quad
            trackContinued(matchToTrack.quad, now)
            return
        }

        val pending = pendingQuad
        if (pending != null) {
            val matchToPending = Contours.bestCornerAssignment(raw.points, pending)
            if (asFraction(matchToPending.totalDistance) < JUMP_DISTANCE_FRACTION) {
                pendingQuad = matchToPending.quad
                pendingStreak++
            } else {
                pendingQuad = raw
                pendingStreak = 1
            }
        } else {
            pendingQuad = raw
            pendingStreak = 1
        }

        if (pendingStreak >= JUMP_CONFIRM_FRAME_COUNT) {
            // A genuinely different shape: ease the track there through the existing filters rather than snapping.
            val confirmed = pendingQuad!!
            pendingQuad = null
            pendingStreak = 0
            lastRawQuad = confirmed
            retargetTrack(confirmed, now)
        }
    }

    /** Clears all filter/track state and publishes null; called after a capture so a new document doesn't inherit the old one's track. */
    fun reset() = resetState()

    private fun asFraction(totalCornerDistance: Double): Double = (totalCornerDistance / 4) / NORMALIZED_SPACE_DIAGONAL

    private fun seedTrack(raw: Quad, now: Long) {
        val newFilters = List(8) { OneEuroFilter(MIN_CUTOFF_HZ, BETA, DERIVATIVE_CUTOFF_HZ) }
        val scalars = raw.toScalars()
        for (i in 0 until 8) {
            newFilters[i].seed(scalars[i])
        }
        filters = newFilters
        lastSampleAt = now
        publish(raw)
    }

    /** Moves an existing track onto a confirmed new shape without resetting the filters, so the corners travel there over the next few frames. */
    private fun retargetTrack(confirmed: Quad, now: Long) {
        val displayed = smoothedQuad
        if (displayed == null || filters == null) {
            seedTrack(confirmed, now)
            return
        }
        // Against the displayed quad, not the abandoned track: the corners travel from where they are drawn.
        val aligned = Contours.bestCornerAssignment(confirmed.points, displayed).quad
        trackContinued(aligned, now)
    }

    private fun trackContinued(corresponded: Quad, now: Long) {
        val dtSeconds = max((now - lastSampleAt!!) / 1e6, 0.001)
        lastSampleAt = now

        val rawScalars = corresponded.toScalars()
        val f = filters!!
        val smoothed = DoubleArray(8) { f[it].filter(rawScalars[it], dtSeconds) }
        publish(quadOf(smoothed))
    }

    private fun resetState() {
        filters = null
        lastSampleAt = null
        lastSeenAt = null
        lastRawQuad = null
        pendingQuad = null
        pendingStreak = 0
        publish(null)
    }

    /** Like `ValueNotifier.value =`: listeners hear about it only if the value is a different instance. */
    private fun publish(value: Quad?) {
        if (value === smoothedQuad) return
        smoothedQuad = value
        onChanged?.invoke(value)
    }
}

/**
 * A single-scalar One Euro Filter (Casiez, Roussel & Vogel, 2012): an adaptive low-pass filter whose cutoff rises with the signal's estimated speed,
 * smoothing heavily when near-stationary and tracking closely when moving fast. `dt` is the actual elapsed time, since the live pipeline's sample
 * rate is irregular.
 */
internal class OneEuroFilter(
    private val minCutoffHz: Double,
    private val beta: Double,
    private val derivativeCutoffHz: Double,
) {
    private var xPrev: Double? = null
    private var dxPrev = 0.0

    fun seed(x: Double) {
        xPrev = x
        dxPrev = 0.0
    }

    fun filter(x: Double, dt: Double): Double {
        val prev = xPrev
        if (prev == null) {
            seed(x)
            return x
        }

        val dx = (x - prev) / dt
        val alphaD = alpha(derivativeCutoffHz, dt)
        val dxHat = alphaD * dx + (1 - alphaD) * dxPrev

        val cutoff = minCutoffHz + beta * abs(dxHat)
        val a = alpha(cutoff, dt)
        val xHat = a * x + (1 - a) * prev

        xPrev = xHat
        dxPrev = dxHat
        return xHat
    }

    private fun alpha(cutoffHz: Double, dt: Double): Double {
        val tau = 1 / (2 * Math.PI * cutoffHz)
        return 1 / (1 + tau / dt)
    }
}
