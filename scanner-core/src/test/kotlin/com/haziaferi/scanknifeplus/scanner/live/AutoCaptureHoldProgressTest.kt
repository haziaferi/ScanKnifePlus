package com.haziaferi.scanknifeplus.scanner.live

import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import java.util.Random
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AutoCaptureDetector.holdProgress], the shutter ring's read-out: it runs from 0 to 1 over a hold, is 1 as the capture fires, drops to 0 when
 * the hold breaks and through the cooldown, and reading it moves no capture (each scenario is run twice, with and without reading it).
 */
class AutoCaptureHoldProgressTest {
    private class FakeClock : MicrosClock {
        var now = 0L
        override fun nowMicros(): Long = now
    }

    /** One run of [quads] (null for a missed detection) 100 ms apart: the capture times, and the progress after each quad if [read]. */
    private class Run(quads: List<Quad?>, read: Boolean) {
        val captures = ArrayList<Long>()
        val progress = ArrayList<Float>()
        val atCapture = ArrayList<Float>()

        init {
            val clock = FakeClock()
            lateinit var detector: AutoCaptureDetector
            detector = AutoCaptureDetector(
                onStable = {
                    captures += clock.now
                    if (read) atCapture += detector.holdProgress
                    detector.notifyCaptured()
                },
                clock = clock,
            )
            for ((i, quad) in quads.withIndex()) {
                clock.now = i * STEP
                detector.onQuadUpdate(quad)
                if (read) progress += detector.holdProgress
            }
        }
    }

    private val noise = Random(5)

    private fun Quad.jitter(sigma: Double = 0.002) = Quad(
        Pt(topLeft.x + noise.nextGaussian() * sigma, topLeft.y + noise.nextGaussian() * sigma),
        Pt(topRight.x + noise.nextGaussian() * sigma, topRight.y + noise.nextGaussian() * sigma),
        Pt(bottomRight.x + noise.nextGaussian() * sigma, bottomRight.y + noise.nextGaussian() * sigma),
        Pt(bottomLeft.x + noise.nextGaussian() * sigma, bottomLeft.y + noise.nextGaussian() * sigma),
    )

    /** Runs [quads] with and without reading the progress, checks both capture at the same times, and returns the reading run. */
    private fun bothWays(quads: List<Quad?>): Run {
        val read = Run(quads, read = true)
        assertEquals("reading the progress moves no capture", Run(quads, read = false).captures, read.captures)
        return read
    }

    @Test
    fun `a steady page fills the ring over 700 ms and captures as it closes`() {
        val run = bothWays(List(10) { PAGE.jitter() })
        assertEquals(listOf(700_000L), run.captures)
        assertEquals(listOf(1f), run.atCapture)
        // Seven steps of 100 ms of a 700 ms hold, then the capture starts the cooldown.
        val expected = (0..6).map { it / 7f } + listOf(0f, 0f, 0f)
        assertEquals(expected.size, run.progress.size)
        for ((i, p) in run.progress.withIndex()) assertEquals("after quad $i", expected[i], p, 1e-6f)
    }

    @Test
    fun `the ring empties when the page jumps, goes missing or the cooldown runs`() {
        val quads = List(5) { PAGE.jitter() } + listOf(OTHER.jitter()) + List(3) { OTHER.jitter() } + listOf(null) + List(10) { PAGE.jitter() }
        val run = bothWays(quads)
        assertEquals("rising before the jump", 4 / 7f, run.progress[4], 1e-6f)
        assertEquals("a jump restarts the hold", 0f, run.progress[5])
        assertEquals(3 / 7f, run.progress[8], 1e-6f)
        assertEquals("a missed detection empties it", 0f, run.progress[9])
        // The hold restarts at quad 10 and fires 700 ms later; the cooldown that follows keeps the ring empty.
        assertEquals(listOf(1_700_000L), run.captures)
        assertEquals(listOf(1f), run.atCapture)
        assertEquals(listOf(0f, 0f, 0f), run.progress.takeLast(3))
    }

    @Test
    fun `a page that slides in captures at the accepted time, its ring filling only once it stops`() {
        val quads = (0..20).map { i -> PAGE.shift(-0.9 * (0.6 - min(i * 0.1, 0.6)), 0.0).ifInFrame()?.jitter() }
        val run = bothWays(quads)
        assertEquals(listOf(1_300_000L), run.captures)
        assertEquals(listOf(1f), run.atCapture)
        assertEquals("the hold starts as the page stops", 0f, run.progress[6])
    }

    @Test
    fun `disabling auto-capture empties the ring`() {
        val clock = FakeClock()
        val detector = AutoCaptureDetector(onStable = {}, clock = clock)
        for (i in 0..3) {
            clock.now = i * STEP
            detector.onQuadUpdate(PAGE)
        }
        assertEquals(3 / 7f, detector.holdProgress, 1e-6f)
        detector.enabled = false
        assertEquals(0f, detector.holdProgress)
    }

    private companion object {
        const val STEP = 100_000L
        val PAGE = Quad(Pt(0.21, 0.16), Pt(0.79, 0.15), Pt(0.81, 0.86), Pt(0.19, 0.85))
        val OTHER = Quad(Pt(0.30, 0.25), Pt(0.95, 0.27), Pt(0.93, 0.70), Pt(0.32, 0.72))

        fun Quad.shift(dx: Double, dy: Double) = Quad(
            Pt(topLeft.x + dx, topLeft.y + dy), Pt(topRight.x + dx, topRight.y + dy),
            Pt(bottomRight.x + dx, bottomRight.y + dy), Pt(bottomLeft.x + dx, bottomLeft.y + dy),
        )

        fun Quad.ifInFrame(): Quad? = takeIf { q -> q.points.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 } }
    }
}
