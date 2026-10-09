package com.haziaferi.scanknifeplus.scanner.live

import com.haziaferi.scanknifeplus.scanner.ParityCase
import com.haziaferi.scanknifeplus.scanner.ParityFixture
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.cv.format
import com.haziaferi.scanknifeplus.scanner.cv.quad
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays OpenScan's scripted live-scan scenarios through the Kotlin smoother, auto-capture detector and live worker (fixture: parity/live.tsv). */
class LiveScanParityTest {
    private val cases = ParityFixture.load("live.tsv")
    private val smootherCases = cases.filter { it.name.startsWith("smoother_") }

    private class FakeClock : MicrosClock {
        var now = 0L
        override fun nowMicros(): Long = now
    }

    private fun steps(c: ParityCase): List<Pair<Long, Quad?>> = c.raw("steps").split('|').map { step ->
        val (micros, q) = step.split(':', limit = 2)
        micros.toLong() to parseQuad(q)
    }

    private fun parseQuad(v: String): Quad? {
        if (v == "null") return null
        val n = v.split(',').map { it.toDouble() }
        return Quad(Pt(n[0], n[1]), Pt(n[2], n[3]), Pt(n[4], n[5]), Pt(n[6], n[7]))
    }

    @Test
    fun `fixture covers jumps, misses and the live worker`() {
        assertEquals(6, smootherCases.size)
        assertTrue(smootherCases.all { it.raw("steps").contains(":null") })
        assertTrue(cases.any { it.name == "live_worker" })
    }

    @Test
    fun `QuadSmoother output and notifications match OpenScan step by step`() {
        for (c in smootherCases) {
            val clock = FakeClock()
            var notifications = 0
            val smoother = QuadSmoother(clock) { notifications++ }
            val outputs = ArrayList<String>()
            for ((micros, quad) in steps(c)) {
                clock.now = micros
                smoother.onRawQuad(quad)
                outputs += "${smoother.smoothedQuad.format()}#$notifications"
            }
            smoother.reset()
            outputs += "${smoother.smoothedQuad.format()}#$notifications"
            val expected = c.raw("outputs").split('|')
            for (i in expected.indices) {
                assertEquals("${c.name}: step $i", expected[i], outputs[i])
            }
            assertEquals("${c.name}: step count", expected.size, outputs.size)
        }
    }

    @Test
    fun `AutoCaptureDetector events match OpenScan`() {
        for (c in smootherCases) {
            val clock = FakeClock()
            val events = ArrayList<String>()
            lateinit var detector: AutoCaptureDetector
            detector = AutoCaptureDetector(
                onStable = {
                    events += "stable@${clock.now}"
                    detector.notifyCaptured()
                },
                onImminentChanged = { events += "imminent=$it@${clock.now}" },
                clock = clock,
            )
            val smoother = QuadSmoother(clock)
            for ((micros, quad) in steps(c)) {
                clock.now = micros
                smoother.onRawQuad(quad)
                detector.onQuadUpdate(smoother.smoothedQuad)
                events += "cooldown=${detector.isInCooldown}"
            }
            detector.enabled = false
            events += "disabled"
            assertEquals(c.name, c.raw("auto_events"), events.joinToString("|"))
        }
    }

    @Test
    fun `live worker and controller match OpenScan, including forgetting the previous quad`() {
        val c = cases.single { it.name == "live_worker" }
        val frameNames = c.raw("frames").split(',')
        val expected = c.raw("results").split('|')

        // The worker on its own.
        val worker = LiveDetectionWorker()
        for ((i, name) in frameNames.withIndex()) {
            val quad = worker.process(c.bytes("frame_$name"), 320, 240)
            assertEquals("worker frame $i", expected[i], "$name=${quad?.let { rotateQuadForPortrait(it, 320, 240) }.format()}")
        }

        // The threaded controller, one frame at a time.
        var latest: Quad? = null
        var latch = CountDownLatch(1)
        val controller = LiveScanController(onResult = { q, _ ->
            latest = q
            latch.countDown()
        })
        try {
            for ((i, name) in frameNames.withIndex()) {
                latch = CountDownLatch(1)
                assertTrue(controller.submitFrame(c.bytes("frame_$name"), 320, 240))
                assertTrue("frame $i timed out", latch.await(30, TimeUnit.SECONDS))
                assertEquals("controller frame $i", expected[i], "$name=${latest.format()}")
            }
        } finally {
            controller.dispose()
        }
        assertFalse(controller.submitFrame(ByteArray(4), 2, 2))
    }

    @Test
    fun `rotateQuadForPortrait matches OpenScan`() {
        for (c in cases.filter { it.name.startsWith("rotate_") }) {
            assertEquals(c.name, c.raw("rotated"), rotateQuadForPortrait(c.quad("quad")!!, 320, 240).format())
        }
    }
}
