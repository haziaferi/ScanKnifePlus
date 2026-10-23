package com.haziaferi.scanknifeplus.scanner.live

import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import java.util.Random
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retuned stillness rule, one scenario class per test: seeded synthetic quads in normalized coordinates (a page filling about 60% x 70% of
 * the frame, 0.2% corner noise) at 10 detections a second, the app's rate, and at 5 or 30 where the rate matters. Every capture is followed by
 * [AutoCaptureDetector.notifyCaptured], as the app does.
 */
class AutoCaptureDetectorTest {
    private class FakeClock : MicrosClock {
        var now = 0L
        override fun nowMicros(): Long = now
    }

    private val clock = FakeClock()
    private val captures = ArrayList<Long>()
    private val imminent = ArrayList<Boolean>()
    private val noise = Random(7)
    private var detector = newDetector()

    private fun newDetector(): AutoCaptureDetector {
        lateinit var d: AutoCaptureDetector
        d = AutoCaptureDetector(
            onStable = {
                captures += clock.now
                d.notifyCaptured()
            },
            onImminentChanged = { imminent += it },
            clock = clock,
        )
        return d
    }

    /** Starts over at time zero with a new detector. */
    private fun fresh() {
        clock.now = 0
        captures.clear()
        imminent.clear()
        detector = newDetector()
    }

    /** Feeds [hz] detections a second for [seconds] from time zero, each from [quadAt] (seconds since the start). */
    private fun feed(hz: Int, seconds: Double = 6.0, quadAt: (Double) -> Quad?) {
        val step = 1_000_000L / hz
        var i = 0L
        while (i * step <= seconds * 1_000_000) {
            clock.now = i++ * step
            detector.onQuadUpdate(quadAt(clock.now / 1e6))
        }
    }

    private fun Quad.jitter(sigma: Double = 0.002) = Quad(
        Pt(topLeft.x + noise.nextGaussian() * sigma, topLeft.y + noise.nextGaussian() * sigma),
        Pt(topRight.x + noise.nextGaussian() * sigma, topRight.y + noise.nextGaussian() * sigma),
        Pt(bottomRight.x + noise.nextGaussian() * sigma, bottomRight.y + noise.nextGaussian() * sigma),
        Pt(bottomLeft.x + noise.nextGaussian() * sigma, bottomLeft.y + noise.nextGaussian() * sigma),
    )

    /** Hand tremor: an 8 Hz and a 9 Hz sine on the two axes, [amplitude] as a fraction of the frame. */
    private fun shaken(amplitude: Double, t: Double) = PAGE.shift(amplitude * sin(2 * PI * 8 * t + 1.3), amplitude * sin(2 * PI * 9 * t + 0.4))

    @Test
    fun `a steady page captures at the first detection 700 ms in, whatever the detection rate`() {
        for (hz in listOf(5, 10, 30)) {
            fresh()
            feed(hz, seconds = 1.0) { PAGE.jitter() }
            val step = 1_000_000L / hz
            val firstAfter = (AutoCaptureDetector.MIN_STABLE_DURATION_MICROS + step - 1) / step * step
            assertEquals("$hz Hz", listOf(firstAfter), captures)
        }
    }

    @Test
    fun `a burst of quick results cannot capture before 700 ms`() {
        for (i in 0 until 60) {
            clock.now = i * 10_000L
            detector.onQuadUpdate(PAGE.jitter())
        }
        assertEquals(emptyList<Long>(), captures)
    }

    @Test
    fun `hand shake up to 2% of the frame still captures`() {
        for ((hz, amplitude) in listOf(30 to 0.01, 30 to 0.02, 10 to 0.01)) {
            fresh()
            feed(hz, seconds = 1.5) { shaken(amplitude, it).jitter() }
            assertEquals("$amplitude at $hz Hz: $captures", 1, captures.size)
        }
    }

    @Test
    fun `hand shake up to 2% of the frame still captures at the app's rate, through the smoother`() {
        // At 10 detections a second an 8-9 Hz tremor aliases into a slow sway; QuadSmoother, which the app puts in front, damps it.
        for (amplitude in listOf(0.01, 0.015, 0.02)) {
            fresh()
            val smoother = QuadSmoother(clock) { detector.onQuadUpdate(it) }
            for (i in 0..15) {
                clock.now = i * 100_000L
                smoother.onRawQuad(shaken(amplitude, clock.now / 1e6).jitter())
            }
            assertEquals("$amplitude: $captures", 1, captures.size)
        }
    }

    @Test
    fun `a page moving at 5% of the frame per second or faster never captures`() {
        for (hz in listOf(10, 30)) {
            for (speed in listOf(0.05, 0.10, 0.15)) {
                fresh()
                feed(hz) { t -> PAGE.shift(-0.1 + speed * t, 0.0).ifInFrame()?.jitter() }
                assertEquals("$speed/s at $hz Hz", emptyList<Long>(), captures)
            }
        }
    }

    @Test
    fun `a page creeping at 1,5% of the frame per second counts as stopped`() {
        feed(10, seconds = 1.0) { t -> PAGE.shift(0.015 * t, 0.0).jitter() }
        assertEquals(listOf(700_000L), captures)
    }

    @Test
    fun `a page that slides in captures 700 ms after it stops`() {
        feed(10, seconds = 2.0) { t -> PAGE.shift(-0.9 * (0.6 - min(t, 0.6)), 0.0).ifInFrame()?.jitter() }
        assertEquals(listOf(1_300_000L), captures)
    }

    @Test
    fun `a phone sweeping across never captures`() {
        feed(30) { t ->
            val p = (0.6 * t) % 2.8
            PAGE.shift(-0.7 + if (p < 1.4) p else 2.8 - p, 0.0).ifInFrame()?.jitter()
        }
        assertEquals(emptyList<Long>(), captures)
    }

    @Test
    fun `a jump to another outline restarts the hold`() {
        feed(10, seconds = 0.5) { PAGE.jitter() }
        val jumpAt = clock.now + 100_000
        clock.now = jumpAt
        detector.onQuadUpdate(OTHER.jitter())
        assertEquals(1, detector.stillQuadCount)
        while (captures.isEmpty()) {
            clock.now += 100_000
            detector.onQuadUpdate(OTHER.jitter())
        }
        assertEquals(jumpAt + 700_000, captures.single())
    }

    @Test
    fun `two outlines alternating every few detections never capture`() {
        var i = 0
        feed(30) { if ((i++ / 5) % 2 == 0) PAGE.jitter() else OTHER.jitter() }
        assertEquals(emptyList<Long>(), captures)
    }

    @Test
    fun `a missed detection restarts the hold`() {
        feed(10, seconds = 0.6) { PAGE.jitter() }
        clock.now += 100_000
        detector.onQuadUpdate(null)
        assertEquals(0, detector.stillQuadCount)
        val restart = clock.now + 100_000
        while (captures.isEmpty()) {
            clock.now += 100_000
            detector.onQuadUpdate(PAGE.jitter())
        }
        assertEquals(restart + 700_000, captures.single())
    }

    @Test
    fun `dropouts within the smoother's grace keep the hold, with or without shake`() {
        // As LiveScanPipeline wires it: a missed detection only reaches the detector once QuadSmoother's grace period has run out.
        for ((amplitude, dropRate) in listOf(0.0 to 0.05, 0.0 to 0.15, 0.015 to 0.15)) {
            fresh()
            val smoother = QuadSmoother(clock) { detector.onQuadUpdate(it) }
            val drops = Random(11)
            var i = 0
            while (captures.isEmpty() && i < 30) {
                clock.now = i * 100_000L
                smoother.onRawQuad(shaken(amplitude, clock.now / 1e6).jitter().takeIf { i == 0 || drops.nextDouble() >= dropRate })
                i++
            }
            assertEquals("shake $amplitude, dropouts $dropRate", 1, captures.size)
            assertTrue("shake $amplitude, dropouts $dropRate: ${captures.single()}", captures.single() <= 1_000_000L)
        }
    }

    @Test
    fun `a hold longer than 700 ms keeps only the last 700 ms`() {
        val holding = AutoCaptureDetector(onStable = {}, clock = clock)
        for (i in 0..50) {
            clock.now = i * 100_000L
            holding.onQuadUpdate(PAGE.jitter())
        }
        assertEquals(8, holding.stillQuadCount)
    }

    @Test
    fun `the cooldown holds off the next capture for 2 s`() {
        feed(10, seconds = 4.0) { PAGE.jitter() }
        assertEquals(listOf(700_000L, 3_400_000L), captures)
    }

    @Test
    fun `imminent is on as soon as a document is tracked, as in OpenScan, and off with no document or when disabled`() {
        detector.onQuadUpdate(PAGE)
        assertEquals(listOf(true), imminent)
        assertEquals(1, detector.stillQuadCount)
        detector.onQuadUpdate(null)
        assertEquals(listOf(true, false), imminent)
        assertEquals(0, detector.stillQuadCount)
        detector.onQuadUpdate(PAGE)
        detector.enabled = false
        assertEquals(listOf(true, false, true, false), imminent)
        detector.onQuadUpdate(PAGE)
        assertEquals(0, detector.stillQuadCount)
    }

    private companion object {
        val PAGE = Quad(Pt(0.21, 0.16), Pt(0.79, 0.15), Pt(0.81, 0.86), Pt(0.19, 0.85))
        val OTHER = Quad(Pt(0.30, 0.25), Pt(0.95, 0.27), Pt(0.93, 0.70), Pt(0.32, 0.72))

        fun Quad.shift(dx: Double, dy: Double) = Quad(
            Pt(topLeft.x + dx, topLeft.y + dy), Pt(topRight.x + dx, topRight.y + dy),
            Pt(bottomRight.x + dx, bottomRight.y + dy), Pt(bottomLeft.x + dx, bottomLeft.y + dy),
        )

        /** A detector only reports a page that is wholly in frame. */
        fun Quad.ifInFrame(): Quad? = takeIf { q -> q.points.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 } }
    }
}
