package com.haziaferi.scanknifeplus.scan.live

import com.haziaferi.scanknifeplus.scan.camera.CameraLens
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.haziaferi.scanknifeplus.scanner.live.FrameAdapter
import com.haziaferi.scanknifeplus.scanner.live.MicrosClock
import java.nio.ByteBuffer
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frame and detection glue ported from OpenScan live_scan_screen.dart, driven with synthetic Y planes, a fake detector and a fake clock. */
class LiveScanPipelineTest {
    private class FakeClock : MicrosClock {
        var now = 0L
        override fun nowMicros(): Long = now
    }

    private class Submitted(val gray: ByteArray, val width: Int, val height: Int, val mapping: QuadMapping)

    private class FakeDetector(val onQuad: (Quad?, QuadMapping) -> Unit) : QuadDetector {
        var busy = false
        var disposed = false
        val submitted = mutableListOf<Submitted>()
        override val isBusy: Boolean get() = busy
        override fun submitFrame(gray: ByteArray, width: Int, height: Int, mapping: QuadMapping): Boolean {
            submitted += Submitted(gray.copyOf(), width, height, mapping)
            return true
        }
        override fun dispose() {
            disposed = true
        }
    }

    private class Recorder : LiveScanPipeline.Listener {
        val events = mutableListOf<String>()
        val quads = mutableListOf<Quad?>()
        val captures = mutableListOf<CaptureRequest>()
        var onCapture: (CaptureRequest) -> Unit = {}
        var onQuad: () -> Unit = {}
        override fun onSmoothedQuadChanged(quad: Quad?) {
            quads += quad
            events += "quad"
            onQuad()
        }
        override fun onLowLightChanged(lowLight: Boolean) {
            events += "lowLight=$lowLight"
        }
        override fun onAutoCaptureImminentChanged(imminent: Boolean) {
            events += "imminent=$imminent"
        }
        override fun onAutoCapture(request: CaptureRequest) {
            captures += request
            events += "capture"
            onCapture(request)
        }
    }

    private val clock = FakeClock()
    private val recorder = Recorder()
    private lateinit var detector: FakeDetector
    private val pipeline = LiveScanPipeline(recorder, clock) { onQuad -> FakeDetector(onQuad).also { detector = it } }

    private val doc = Quad(Pt(0.2, 0.15), Pt(0.8, 0.15), Pt(0.8, 0.85), Pt(0.2, 0.85))

    /** A lopsided quad and the same quad turned half way round, worked out by hand. */
    private val skewed = Quad(Pt(0.1, 0.2), Pt(0.7, 0.15), Pt(0.75, 0.9), Pt(0.05, 0.8))
    private val skewedHalfTurn = Quad(Pt(0.25, 0.1), Pt(0.95, 0.2), Pt(0.9, 0.8), Pt(0.3, 0.85))

    /** A uniform landscape frame with row padding, as CameraX hands it over (direct buffer, unpadded last row). */
    private fun frame(value: Int, width: Int = 640, height: Int = 480, rowStride: Int = width + 32): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(rowStride * (height - 1) + width)
        for (i in 0 until buf.capacity()) buf.put(i, value.toByte())
        return buf
    }

    private fun feed(count: Int, value: Int = 128, rotation: Int = 90, lens: CameraLens = CameraLens.BACK) {
        repeat(count) { pipeline.onFrame(frame(value), 672, 640, 480, rotation, lens) }
    }

    /** Delivers [quad] as the detector's result [times] times, [stepMicros] apart, for a frame with [mapping] (by default the last one fed). */
    private fun detect(
        quad: Quad?,
        times: Int = 1,
        stepMicros: Long = 100_000,
        mapping: QuadMapping = detector.submitted.lastOrNull()?.mapping ?: QuadMapping.AS_IS,
    ) {
        repeat(times) {
            clock.now += stepMicros
            detector.onQuad(quad, mapping)
        }
    }

    /** A sensor-native 640x480 Y plane with a bright page from x 160..480, y 80..400 on a dark desk, rows [STRIDE] bytes apart. */
    private fun pagePlane(): ByteArray = ByteArray(STRIDE * 479 + 640) { i ->
        val x = i % STRIDE
        val y = i / STRIDE
        (if (x in 160 until 480 && y in 80 until 400) 220 else 40).toByte()
    }

    @Test
    fun `every third frame is analysed`() {
        feed(9)
        assertEquals(3, detector.submitted.size)
        feed(2)
        assertEquals(3, detector.submitted.size)
        feed(1)
        assertEquals(4, detector.submitted.size)
    }

    @Test
    fun `frames are skipped while detection is busy, without resetting the cadence`() {
        detector.busy = true
        feed(3)
        assertEquals(0, detector.submitted.size)
        detector.busy = false
        feed(2)
        assertEquals(0, detector.submitted.size)
        feed(1)
        assertEquals(1, detector.submitted.size)
    }

    @Test
    fun `frames are skipped while zooming`() {
        pipeline.setZooming(true)
        feed(6, value = 10)
        assertEquals(0, detector.submitted.size)
        assertFalse("low light is not updated mid-zoom either", pipeline.isLowLight)
        pipeline.setZooming(false)
        feed(3)
        assertEquals(1, detector.submitted.size)
    }

    @Test
    fun `the submitted frame is FrameAdapter's downsample of the padded plane, for odd sizes too`() {
        for ((width, height, stride) in listOf(Triple(641, 479, 704), Triple(1001, 333, 1001), Triple(37, 23, 64), Triple(1920, 1080, 1920))) {
            val det = mutableListOf<FakeDetector>()
            val p = LiveScanPipeline(recorder, clock) { onQuad -> FakeDetector(onQuad).also { det += it } }
            val plane = ByteArray(stride * (height - 1) + width) { i -> ((i * 31 + i / stride * 7) and 0xFF).toByte() }
            // Leading junk the pipeline must skip: it reads from the buffer's position.
            val buf = ByteBuffer.allocate(plane.size + 5)
            buf.put(ByteArray(5) { 0x7F })
            buf.put(plane)
            buf.position(5)
            repeat(3) { p.onFrame(buf, stride, width, height, 90, CameraLens.BACK) }
            assertEquals("position is left alone", 5, buf.position())

            val sub = det.single().submitted.single()
            val (w, h) = FrameAdapter.downsampledSize(width, height)!!
            assertEquals(w, sub.width)
            assertEquals(h, sub.height)
            assertArrayEquals(FrameAdapter.grayscaleFromYPlane(plane, stride, width, height), sub.gray)
        }
    }

    @Test
    fun `a buffer shorter than its geometry, or a stride below the width, is dropped`() {
        val short = ByteBuffer.allocate(672 * 479 + 639)
        repeat(3) { pipeline.onFrame(short, 672, 640, 480, 90, CameraLens.BACK) }
        repeat(3) { pipeline.onFrame(frame(128, rowStride = 640), 600, 640, 480, 90, CameraLens.BACK) }
        repeat(3) { pipeline.onFrame(frame(128), 672, 0, 480, 90, CameraLens.BACK) }
        assertEquals(0, detector.submitted.size)
    }

    @Test
    fun `low light turns on below 55 and off only at 63, once each way`() {
        feed(3, value = 54)
        assertTrue(pipeline.isLowLight)
        feed(3, value = 62)
        assertTrue("inside the dead band it stays on", pipeline.isLowLight)
        feed(3, value = 63)
        assertFalse(pipeline.isLowLight)
        feed(3, value = 55)
        assertFalse("55 is not dark", pipeline.isLowLight)
        assertEquals(listOf("lowLight=true", "lowLight=false"), recorder.events)
    }

    @Test
    fun `raw quads are smoothed and published`() {
        detect(doc)
        assertSame(doc, pipeline.smoothedQuad)
        assertEquals(listOf<Quad?>(doc), recorder.quads)
        detect(null, stepMicros = 600_000)
        assertNull(pipeline.smoothedQuad)
    }

    @Test
    fun `a still document auto-captures once, with the smoothed quad`() {
        detect(doc, times = 7)
        assertTrue(pipeline.isAutoCaptureImminent)
        assertEquals(0, recorder.captures.size)
        detect(doc)
        assertEquals(1, recorder.captures.size)
        assertTrue(pipeline.isCapturing)
        assertQuadNear(doc, recorder.captures.single().quad!!)

        detect(doc, times = 5)
        assertEquals("no second capture while one is in progress", 1, recorder.captures.size)
        assertNull("a manual shutter is refused too", pipeline.beginCapture())
    }

    @Test
    fun `after a capture the track is dropped and the cooldown holds off the next one`() {
        detect(doc, times = 8)
        assertEquals(1, recorder.captures.size)
        pipeline.notifyCaptured()
        assertNull(pipeline.smoothedQuad)
        assertNull(recorder.quads.last())
        assertFalse(pipeline.isAutoCaptureImminent)
        pipeline.endCapture()
        assertFalse(pipeline.isCapturing)

        detect(doc, times = 19) // 1.9 s, still inside the 2 s cooldown
        assertEquals(1, recorder.captures.size)
        detect(doc, times = 9)
        assertEquals(2, recorder.captures.size)
    }

    @Test
    fun `a failed capture still starts the cooldown, so a failing camera cannot loop`() {
        detect(doc, times = 8)
        pipeline.endCapture() // no notifyCaptured: the still failed
        assertFalse(pipeline.isCapturing)
        detect(doc, times = 19) // 1.9 s, still inside the 2 s cooldown
        assertEquals(1, recorder.captures.size)
        detect(doc, times = 9)
        assertEquals("auto-capture works again after the cooldown", 2, recorder.captures.size)
    }

    @Test
    fun `a listener may finish the capture from inside onAutoCapture`() {
        recorder.onCapture = {
            pipeline.notifyCaptured()
            pipeline.endCapture()
        }
        detect(doc, times = 8)
        assertEquals(1, recorder.captures.size)
        assertFalse(pipeline.isCapturing)
        assertNull("the track was dropped by notifyCaptured", pipeline.smoothedQuad)
        detect(doc, times = 19)
        assertEquals("the cooldown from notifyCaptured holds", 1, recorder.captures.size)
        detect(doc, times = 9)
        assertEquals(2, recorder.captures.size)
    }

    @Test
    fun `nothing is published after a listener disposes the pipeline from a callback`() {
        // The first steady quad is the one that makes auto-capture imminent; disposing in its callback must suppress that event and the rest.
        recorder.onQuad = { pipeline.dispose() }
        detect(doc, times = 10)
        assertEquals(listOf("quad"), recorder.events)
        assertEquals(0, recorder.captures.size)
        assertTrue(detector.disposed)
    }

    @Test
    fun `frames pause from the shutter until the still is taken`() {
        assertNotNull(pipeline.beginCapture())
        feed(6)
        assertEquals(0, detector.submitted.size)
        pipeline.notifyCaptured()
        feed(3)
        assertEquals(1, detector.submitted.size)
    }

    @Test
    fun `no auto-capture while zooming`() {
        pipeline.setZooming(true)
        detect(doc, times = 20)
        assertNotNull("the overlay still follows", pipeline.smoothedQuad)
        assertEquals(0, recorder.captures.size)
        assertFalse(pipeline.isAutoCaptureImminent)
    }

    @Test
    fun `disabling auto-capture stops it and clears the cue`() {
        detect(doc, times = 3)
        assertTrue(pipeline.isAutoCaptureImminent)
        pipeline.setAutoCaptureEnabled(false)
        assertFalse(pipeline.isAutoCaptureImminent)
        detect(doc, times = 20)
        assertEquals(0, recorder.captures.size)
        pipeline.setAutoCaptureEnabled(true)
        detect(doc, times = 8)
        assertEquals(1, recorder.captures.size)
    }

    @Test
    fun `a back camera at 90 degrees keeps the quad as detected`() {
        feed(3)
        assertEquals(QuadMapping.AS_IS, detector.submitted.single().mapping)
        detect(skewed, times = 8)
        assertQuadNear(skewed, pipeline.smoothedQuad!!)
        assertQuadNear(skewed, recorder.captures.single().quad!!)
    }

    @Test
    fun `a back camera at 270 degrees turns the quad half way round`() {
        feed(3, rotation = 270)
        assertEquals(QuadMapping.HALF_TURN, detector.submitted.single().mapping)
        detect(skewed, times = 8)
        assertQuadNear(skewedHalfTurn, recorder.quads.first()!!)
        assertQuadNear(skewedHalfTurn, pipeline.smoothedQuad!!)
        assertQuadNear(skewedHalfTurn, recorder.captures.single().quad!!)
    }

    @Test
    fun `other rotations and the front camera give no overlay and no capture quad`() {
        for ((rotation, lens) in listOf(0 to CameraLens.BACK, 180 to CameraLens.BACK, 90 to CameraLens.FRONT, 270 to CameraLens.FRONT)) {
            feed(3, rotation = rotation, lens = lens)
            assertEquals(QuadMapping.NONE, detector.submitted.last().mapping)
            detect(doc, times = 8)
            assertNull(pipeline.smoothedQuad)
            assertEquals(0, recorder.captures.size)
            assertNull(pipeline.beginCapture()!!.quad)
            pipeline.endCapture()
        }
        feed(3)
        detect(doc)
        assertNotNull(pipeline.beginCapture()!!.quad)
    }

    @Test
    fun `another camera drops the track and late results from the old one`() {
        feed(3)
        detect(doc)
        assertNotNull(pipeline.smoothedQuad)
        feed(1, rotation = 270)
        assertNull(pipeline.smoothedQuad)
        assertNull(recorder.quads.last())
        detect(doc, mapping = QuadMapping.AS_IS)
        assertNull("a result from the old camera is dropped", pipeline.smoothedQuad)
        assertNull(pipeline.beginCapture()!!.quad)
    }

    @Test
    fun `dispose stops detection and every callback`() {
        detect(doc, times = 3)
        val before = recorder.events.size
        pipeline.dispose()
        assertTrue(detector.disposed)
        feed(6, value = 10)
        assertEquals(0, detector.submitted.size)
        detect(doc, times = 20)
        detect(null, stepMicros = 600_000)
        pipeline.setAutoCaptureEnabled(false)
        pipeline.notifyCaptured()
        assertNull(pipeline.beginCapture())
        assertEquals(before, recorder.events.size)
    }

    @Test
    fun `end to end with the real detector finds a bright page on a dark desk`() {
        val direct = object : AbstractExecutorService() {
            override fun execute(command: Runnable) = command.run()
            override fun shutdown() {}
            override fun shutdownNow(): List<Runnable> = emptyList()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
        }
        val rec = Recorder()
        val p = LiveScanPipeline(rec, clock) { onQuad -> liveQuadDetector(onQuad, direct) }
        val buf = ByteBuffer.wrap(pagePlane())
        repeat(3) { p.onFrame(buf, STRIDE, 640, 480, 90, CameraLens.BACK) }
        val quad = rec.quads.single()!!
        // Rotated into portrait [0,1]: x from the frame's y, y from the frame's x.
        assertQuadNear(Quad(Pt(1 / 6.0, 0.25), Pt(5 / 6.0, 0.25), Pt(5 / 6.0, 0.75), Pt(1 / 6.0, 0.75)), quad, tolerance = 0.03)
        p.dispose()
    }

    @Test
    fun `a detection result carries the mapping of its own frame, whatever was submitted since`() {
        val queued = ArrayDeque<Runnable>()
        val manual = object : AbstractExecutorService() {
            override fun execute(command: Runnable) {
                queued += command
            }
            override fun shutdown() {}
            override fun shutdownNow(): List<Runnable> = emptyList()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
        }
        val results = mutableListOf<QuadMapping>()
        val d = liveQuadDetector({ _, mapping -> results += mapping }, manual)
        val gray = FrameAdapter.grayscaleFromYPlane(pagePlane(), STRIDE, 640, 480)!!
        val (w, h) = FrameAdapter.downsampledSize(640, 480)!!
        assertTrue(d.submitFrame(gray, w, h, QuadMapping.AS_IS))
        assertFalse("busy", d.submitFrame(gray, w, h, QuadMapping.HALF_TURN))
        queued.removeFirst().run()
        assertEquals(listOf(QuadMapping.AS_IS), results)
    }

    private fun assertQuadNear(expected: Quad, actual: Quad, tolerance: Double = 1e-9) {
        for ((e, a) in expected.points.zip(actual.points)) {
            assertTrue("$expected vs $actual", abs(e.x - a.x) <= tolerance && abs(e.y - a.y) <= tolerance)
        }
    }

    private companion object {
        const val STRIDE = 704
    }
}
