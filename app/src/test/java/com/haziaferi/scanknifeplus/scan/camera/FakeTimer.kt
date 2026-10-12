package com.haziaferi.scanknifeplus.scan.camera

import com.haziaferi.scanknifeplus.scanner.live.Cancellable
import com.haziaferi.scanknifeplus.scanner.live.DelayScheduler

/** A [DelayScheduler] driven by hand: [advance] runs the actions that fall due, in order, and [now] is the current time in micros. */
internal class FakeTimer : DelayScheduler {
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
