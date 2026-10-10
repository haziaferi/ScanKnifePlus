package com.haziaferi.scanknifeplus.scanner.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The zoom throttle against a fake clock and a hand-driven timer, following OpenScan's _requestZoomLevel / _onZoomSliderChangeEnd. */
class ZoomThrottleTest {
    /** A clock and a timer queue in one: [advanceTo] moves time forward and runs every timer that has come due, in order. */
    private class FakeTime : MicrosClock, DelayScheduler {
        var now = 1_000_000L
        private class Task(val due: Long, val action: () -> Unit) { var cancelled = false }
        private val tasks = ArrayList<Task>()

        val scheduledCount: Int get() = tasks.count { !it.cancelled }
        val delays = ArrayList<Long>()

        override fun nowMicros(): Long = now

        override fun schedule(delayMicros: Long, action: () -> Unit): Cancellable {
            delays += delayMicros
            val task = Task(now + delayMicros, action)
            tasks += task
            return Cancellable { task.cancelled = true }
        }

        fun advanceTo(t: Long) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.due <= t }.minByOrNull { it.due } ?: break
                tasks.remove(next)
                now = next.due
                next.action()
            }
            now = t
        }
    }

    private val time = FakeTime()
    private val calls = ArrayList<Pair<Long, Float>>()
    private val throttle = ZoomThrottle({ calls += time.now to it }, time, time)

    private val ms = 1_000L

    @Test
    fun `first request is issued at once`() {
        throttle.request(2f)
        assertEquals(listOf(time.now to 2f), calls)
        assertTrue(throttle.isZooming)
        assertEquals(0, time.scheduledCount)
    }

    @Test
    fun `requests inside the interval coalesce into one timed call carrying the latest target`() {
        val t0 = time.now
        throttle.request(1.5f)
        time.advanceTo(t0 + 10 * ms)
        throttle.request(1.6f)
        time.advanceTo(t0 + 20 * ms)
        throttle.request(1.7f)
        time.advanceTo(t0 + 59 * ms)
        throttle.request(1.8f)
        assertEquals(1, calls.size)
        assertEquals(listOf(50 * ms), time.delays) // the remainder of the interval, measured from the first early request
        time.advanceTo(t0 + 60 * ms)
        assertEquals(listOf(t0 to 1.5f, t0 + 60 * ms to 1.8f), calls)
        assertNull(throttle.pending)
    }

    @Test
    fun `calls are never closer than the interval during a long drag`() {
        val t0 = time.now
        for (i in 0..100) {
            time.advanceTo(t0 + i * 7 * ms)
            throttle.request(1f + i / 100f)
        }
        time.advanceTo(t0 + 1_000 * ms)
        calls.zipWithNext().forEach { (a, b) -> assertTrue("${a.first} -> ${b.first}", b.first - a.first >= ZoomThrottle.INTERVAL_MICROS) }
        assertEquals(2f, calls.last().second, 0f) // the drag's final target always lands
    }

    @Test
    fun `a request at exactly the interval is issued directly`() {
        val t0 = time.now
        throttle.request(1f)
        time.advanceTo(t0 + ZoomThrottle.INTERVAL_MICROS)
        throttle.request(3f)
        assertEquals(listOf(t0 to 1f, t0 + 60 * ms to 3f), calls)
        assertTrue(time.delays.isEmpty())
    }

    @Test
    fun `finish flushes the held-back target at once and cancels the timer`() {
        val t0 = time.now
        throttle.request(1f)
        time.advanceTo(t0 + 5 * ms)
        throttle.request(4f)
        throttle.finish()
        assertEquals(listOf(t0 to 1f, t0 + 5 * ms to 4f), calls)
        assertFalse(throttle.isZooming)
        time.advanceTo(t0 + 500 * ms)
        assertEquals(2, calls.size) // the cancelled timer does not fire a second call
    }

    @Test
    fun `finish with nothing pending issues nothing`() {
        throttle.request(2f)
        throttle.finish()
        assertEquals(1, calls.size)
        assertFalse(throttle.isZooming)
    }

    @Test
    fun `cancel drops the pending target`() {
        val t0 = time.now
        throttle.request(1f)
        time.advanceTo(t0 + 5 * ms)
        throttle.request(4f)
        throttle.cancel()
        time.advanceTo(t0 + 500 * ms)
        assertEquals(listOf(t0 to 1f), calls)
        assertFalse(throttle.isZooming)
        assertNull(throttle.pending)
    }

    @Test
    fun `the interval counts from the last issued call, including a flushed one`() {
        val t0 = time.now
        throttle.request(1f)
        time.advanceTo(t0 + 30 * ms)
        throttle.request(2f)
        throttle.finish() // issued at t0 + 30 ms
        time.advanceTo(t0 + 70 * ms)
        throttle.request(3f) // only 40 ms after the flush: held for 20 ms
        assertEquals(2, calls.size)
        assertEquals(20 * ms, time.delays.last())
        time.advanceTo(t0 + 90 * ms)
        assertEquals(listOf(t0 to 1f, t0 + 30 * ms to 2f, t0 + 90 * ms to 3f), calls)
    }
}
