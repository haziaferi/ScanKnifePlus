// Test double ported from OSS-DocumentScanner (https://github.com/ossappscollective/OSS-DocumentScanner) at commit
// ff05fc52b601d1cccb4076f3f6c3318f74e72f0c: plugin-nativeprocessor/platforms/android/java/com/akylas/documentscanner/AutoScanHandler.kt
// (and the HashBiMap semantics of BiMap.kt it relies on). MIT License, Copyright (c) 2022 Martin Guillon. Benchmark only, never shipped.
package com.haziaferi.scanknifeplus.scanner.bench

import java.util.PriorityQueue
import kotlin.math.sqrt

/** android.graphics.Point: integer pixels, with Android's `31 * x + y` hash. */
data class IntPoint(val x: Int, val y: Int) {
    val androidHash: Int get() = 31 * x + y

    fun distance(other: IntPoint): Double {
        val dx = (other.x - x).toDouble()
        val dy = (other.y - y).toDouble()
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * OSS-DocumentScanner's AutoScanHandler timing logic on a virtual clock: coroutine `delay`s become timers that [advanceTo] fires in time
 * order, so a run is deterministic. Every frame calls [advanceTo] with the frame time and then [process] with that frame's quads.
 *
 * Differences from the original, all forced by the JVM setting: the jobs' `delay(10)` x 101 progress loop takes exactly 1010 ms (the real
 * dispatcher adds scheduling slack); the crop-view progress calls are dropped; and a frame may carry at most one quad. Their detector returns
 * one quad per frame, and with more than one the original's `mutableList.remove` inside `forEach` only survives on Android's ArrayList
 * iterator (which stops at the size it started with) when the list had one element; with two or more it throws, so [process] refuses them.
 */
class AutoScanHandlerDouble(private val onAutoScan: (List<IntPoint>) -> Unit) {
    var distanceThreshold = 50.0f
    var preAutoScanDelay = 1000L
    var autoScanDuration = 1000.0f
    var enabled = true

    private var now = 0L
    private var seq = 0L
    private val timers = PriorityQueue<Timer>(compareBy<Timer>({ it.at }, { it.seq }))
    private var currentPoints: List<List<IntPoint>>? = null
    private val direct = HashMap<Long, Long>()
    private val reverse = HashMap<Long, Long>()
    private val autoScanJobs = HashMap<Long, Job>()
    private val preAutoScanJobs = HashMap<Long, Job>()

    private class Job {
        var cancelled = false
    }

    private class Timer(val at: Long, val seq: Long, val job: Job, val action: () -> Unit)

    /** Runs every timer due at or before [timeMs], in order, then sets the clock to [timeMs]. */
    fun advanceTo(timeMs: Long) {
        while (timers.isNotEmpty() && timers.peek().at <= timeMs) {
            val t = timers.poll()
            now = t.at
            if (!t.job.cancelled) t.action()
        }
        now = timeMs
    }

    fun process(points: List<List<IntPoint>>?) {
        require((points?.size ?: 0) <= 1) { "more than one quad per frame throws ConcurrentModificationException in the original" }
        if (!enabled || points.isNullOrEmpty()) {
            clearAll()
            return
        } else if (currentPoints == null) {
            points.forEach { startAutoScanPreJob(it) }
        } else {
            val mutableList = points.toMutableList()
            for (existing in currentPoints!!) {
                val hash = getHash(existing)
                val originalHash = direct[hash] ?: hash
                var found = false
                for (comparing in mutableList.toList()) {
                    val distance = (0 until 4).maxOf { existing[it].distance(comparing[it]) }
                    if (distance < distanceThreshold) {
                        if (!autoScanJobs.containsKey(originalHash) && !preAutoScanJobs.containsKey(originalHash)) {
                            // The original does not restart a scan on the same document.
                            mutableList.remove(comparing)
                        } else {
                            replaceHash(hash, getHash(comparing))
                            mutableList.remove(comparing)
                        }
                        found = true
                        break
                    }
                }
                if (!found) {
                    autoScanJobs.remove(originalHash)?.cancelled = true
                    preAutoScanJobs.remove(originalHash)?.cancelled = true
                }
            }
            mutableList.forEach { startAutoScanPreJob(it) }
        }
        currentPoints = points
    }

    private fun clearAll() {
        autoScanJobs.values.forEach { it.cancelled = true }
        autoScanJobs.clear()
        preAutoScanJobs.values.forEach { it.cancelled = true }
        preAutoScanJobs.clear()
        currentPoints = null
    }

    /** HashBiMap.forcePut, as `hashMapping[newValue] = ...` does. */
    private fun forcePut(key: Long, value: Long) {
        direct.put(key, value)?.let { reverse.remove(it) }
        reverse.put(value, key)?.let { if (it != key) direct.remove(it) }
    }

    private fun replaceHash(oldValue: Long, newValue: Long) {
        val original = direct[oldValue]
        if (original != null) {
            direct.remove(oldValue)?.let { reverse.remove(it) }
            forcePut(newValue, original)
        } else {
            forcePut(newValue, oldValue)
        }
    }

    private fun schedule(delayMs: Long, job: Job, action: () -> Unit) {
        timers.add(Timer(now + delayMs, seq++, job, action))
    }

    private fun startAutoScanPreJob(points: List<IntPoint>) {
        val hash = getHash(points)
        val job = Job()
        preAutoScanJobs[hash] = job
        schedule(preAutoScanDelay, job) {
            startAutoScanJob(points)
            preAutoScanJobs.remove(hash)
        }
    }

    private fun startAutoScanJob(points: List<IntPoint>) {
        val hash = getHash(points)
        val delayMs = (autoScanDuration / 100.0f).toLong()
        val job = Job()
        autoScanJobs[hash] = job
        schedule(delayMs * 101, job) {
            if (enabled && reverse.containsKey(hash)) onAutoScan(points)
            autoScanJobs.remove(hash)
        }
    }

    companion object {
        fun getHash(points: List<IntPoint>): Long = points[0].androidHash +
            10000L * points[1].androidHash +
            100000L * points[2].androidHash +
            1000000L * points[3].androidHash
    }
}
