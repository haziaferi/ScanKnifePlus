package com.haziaferi.scanknifeplus.scan.camera

import androidx.camera.core.CameraState
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.haziaferi.scanknifeplus.scanner.live.Cancellable
import com.haziaferi.scanknifeplus.scanner.live.DelayScheduler

/**
 * Holds a bind or a resume back until the camera has left OPEN (see [ScanCameraController]), but no longer than [timeoutMicros]. Not thread-safe: use it on
 * the main thread, where LiveData delivers, and let [scheduler] run its actions there too.
 */
internal class CloseWait(private val scheduler: DelayScheduler, private val timeoutMicros: Long) {
    private var stop: (() -> Unit)? = null

    /**
     * Starts waiting, replacing any earlier wait: [onDone] runs once, when [states] holds anything but OPEN or once [timeoutMicros] have passed,
     * with whether it timed out; never after [cancel].
     */
    fun start(states: LiveData<CameraState>, onDone: (timedOut: Boolean) -> Unit) {
        cancel()
        val observer = object : Observer<CameraState> {
            override fun onChanged(value: CameraState) {
                if (value.type == CameraState.Type.OPEN) return
                cancel()
                onDone(false)
            }
        }
        var timer: Cancellable? = null
        stop = {
            states.removeObserver(observer)
            timer?.cancel()
        }
        timer = scheduler.schedule(timeoutMicros) {
            cancel()
            onDone(true)
        }
        states.observeForever(observer)
    }

    /** Stops the wait without calling its onDone; harmless when not waiting. */
    fun cancel() {
        val s = stop ?: return
        stop = null
        s()
    }
}
