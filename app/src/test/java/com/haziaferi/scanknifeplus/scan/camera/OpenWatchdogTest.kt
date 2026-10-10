package com.haziaferi.scanknifeplus.scan.camera

import com.haziaferi.scanknifeplus.scanner.live.Cancellable
import com.haziaferi.scanknifeplus.scanner.live.DelayScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

/** The camera-open watchdog against a hand-driven timer: one retry after a timeout, the error only after a second one. Plain JVM. */
class OpenWatchdogTest {
    private class FakeTimer : DelayScheduler {
        var now = 0L
        private class Task(val due: Long, val action: () -> Unit) { var cancelled = false }
        private val tasks = ArrayList<Task>()

        override fun schedule(delayMicros: Long, action: () -> Unit): Cancellable {
            val task = Task(now + delayMicros, action)
            tasks += task
            return Cancellable { task.cancelled = true }
        }

        fun advance(micros: Long) {
            val end = now + micros
            while (true) {
                val next = tasks.filter { !it.cancelled && it.due <= end }.minByOrNull { it.due } ?: break
                tasks.remove(next)
                now = next.due
                next.action()
            }
            now = end
        }
    }

    private val timer = FakeTimer()
    private val events = ArrayList<String>()
    private val timeout = 5_000_000L

    // Like the controller, a retry releases the camera (pause) and binds again (opening).
    private lateinit var watchdog: OpenWatchdog

    init {
        watchdog = OpenWatchdog(
            timer,
            timeout,
            onRetry = {
                events += "retry@${timer.now}"
                watchdog.pause()
                watchdog.opening()
            },
            onTimeout = { events += "timeout@${timer.now}" },
        )
    }

    @Test
    fun `an open that finishes in time does nothing`() {
        watchdog.opening()
        timer.advance(4_000_000)
        watchdog.reset()
        timer.advance(60_000_000)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a hung open is retried once and reported only when the retry hangs too`() {
        watchdog.opening()
        timer.advance(20_000_000)
        assertEquals(listOf("retry@5000000", "timeout@10000000"), events)
    }

    @Test
    fun `a retry that opens clears the spent retry`() {
        watchdog.opening()
        timer.advance(5_000_000)
        assertEquals(listOf("retry@5000000"), events)
        watchdog.reset() // the retry opened
        watchdog.opening() // later the camera reopens (screen restarted) and hangs
        timer.advance(10_000_000)
        assertEquals(listOf("retry@5000000", "retry@10000000", "timeout@15000000"), events)
    }

    @Test
    fun `repeated opening reports do not restart the timer`() {
        watchdog.opening()
        timer.advance(3_000_000)
        watchdog.opening() // OPENING after PENDING_OPEN
        timer.advance(2_000_000)
        assertEquals(listOf("retry@5000000"), events)
    }

    @Test
    fun `a closed camera stops the timer`() {
        watchdog.opening()
        timer.advance(3_000_000)
        watchdog.reset()
        timer.advance(30_000_000)
        assertEquals(emptyList<String>(), events)
    }
}
