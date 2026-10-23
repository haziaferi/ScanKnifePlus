package com.haziaferi.scanknifeplus.scanner.live

import com.haziaferi.scanknifeplus.scanner.ParityCase
import com.haziaferi.scanknifeplus.scanner.ParityFixture
import com.haziaferi.scanknifeplus.scanner.cv.DocumentDetector
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.cv.format
import com.haziaferi.scanknifeplus.scanner.cv.parseQuad
import com.haziaferi.scanknifeplus.scanner.cv.quad
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays OpenScan's scripted live-scan scenarios through the Kotlin smoother and live worker (fixture: parity/live.tsv).
 * The detector itself no longer matches OpenScan's (it searches OSS-DocumentScanner's candidates first), so the live worker's quads are checked
 * against the shipped detector under the worker's previous-quad rule; which frames find a quad, and the notifications, still match OpenScan.
 */
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

    /**
     * What the worker should publish for [frameNames]: the shipped detector with the last quad fed back as `previousQuad` and dropped after
     * [LiveDetectionWorker.FORGET_PREVIOUS_AFTER_MISSES] misses, rotated for portrait.
     */
    private fun expectedResults(c: ParityCase, frameNames: List<String>): List<String> {
        var previous: Quad? = null
        var misses = 0
        return frameNames.map { name ->
            val quad = DocumentDetector.detectQuadFromGrayscale(c.bytes("frame_$name"), 320, 240, previous)
            if (quad != null) {
                previous = quad
                misses = 0
            } else if (++misses >= LiveDetectionWorker.FORGET_PREVIOUS_AFTER_MISSES) {
                previous = null
            }
            "$name=${quad?.let { rotateQuadForPortrait(it, 320, 240) }.format()}"
        }
    }

    @Test
    fun `live worker and controller feed the previous quad back and find a quad on the same frames as OpenScan`() {
        val c = cases.single { it.name == "live_worker" }
        val frameNames = c.raw("frames").split(',')
        val expected = expectedResults(c, frameNames)
        assertEquals(c.raw("results").split('|').map { it.endsWith("=null") }, expected.map { it.endsWith("=null") })

        // The worker on its own.
        val worker = LiveDetectionWorker()
        for ((i, name) in frameNames.withIndex()) {
            val quad = worker.process(c.bytes("frame_$name"), 320, 240)
            assertEquals("worker frame $i", expected[i], "$name=${quad?.let { rotateQuadForPortrait(it, 320, 240) }.format()}")
        }

        // The threaded controller, one frame at a time. Its executor is single-threaded, so a no-op submitted after a frame finishes only once
        // that frame (and its callbacks) are done.
        val expectedNotifications = c.raw("quad_notifications").split(',').map { it.toInt() }
        var notifications = 0
        var latest: Quad? = null
        val executor = Executors.newSingleThreadExecutor()
        val controller = LiveScanController(onQuad = { q ->
            latest = q
            notifications++
        }, executor = executor)
        try {
            for ((i, name) in frameNames.withIndex()) {
                assertTrue(controller.submitFrame(c.bytes("frame_$name"), 320, 240))
                executor.submit {}.get(30, TimeUnit.SECONDS)
                assertEquals("controller frame $i", expected[i], "$name=${latest.format()}")
                assertEquals("controller frame $i", expected[i], "$name=${controller.latestQuad.format()}")
                assertEquals("notifications after frame $i", expectedNotifications[i], notifications)
            }
        } finally {
            controller.dispose()
        }
        assertFalse(controller.submitFrame(ByteArray(4), 2, 2))
    }

    @Test
    fun `controller copies the frame so the caller can reuse its buffer`() {
        val c = cases.single { it.name == "live_worker" }
        val frameNames = c.raw("frames").split(',')
        val expected = expectedResults(c, frameNames)
        val docIndex = frameNames.indexOf("doc131")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val controller = LiveScanController(onQuad = {}, executor = executor)
        try {
            // Hold the worker thread so the frame is still queued when the caller overwrites its buffer.
            executor.execute {
                started.countDown()
                release.await()
            }
            started.await()
            val buffer = c.bytes("frame_doc131")
            assertTrue(controller.submitFrame(buffer, 320, 240))
            buffer.fill(90)
            release.countDown()
            executor.submit {}.get(30, TimeUnit.SECONDS)
            assertEquals(expected[docIndex], "doc131=${controller.latestQuad.format()}")
        } finally {
            controller.dispose()
        }
    }

    @Test
    fun `nothing is published after dispose`() {
        val c = cases.single { it.name == "live_worker" }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var published = 0
        val controller = LiveScanController(onQuad = { published++ }, onLatency = { published++ }, executor = executor)
        executor.execute {
            started.countDown()
            // dispose() interrupts this wait; that is expected here.
            runCatching { release.await() }
        }
        started.await()
        assertTrue(controller.submitFrame(c.bytes("frame_doc131"), 320, 240))
        controller.dispose()
        release.countDown()
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(0, published)
        assertFalse(controller.submitFrame(ByteArray(4), 2, 2))
    }

    @Test
    fun `rotateQuadForPortrait matches OpenScan`() {
        for (c in cases.filter { it.name.startsWith("rotate_") }) {
            assertEquals(c.name, c.raw("rotated"), rotateQuadForPortrait(c.quad("quad")!!, 320, 240).format())
        }
    }
}
