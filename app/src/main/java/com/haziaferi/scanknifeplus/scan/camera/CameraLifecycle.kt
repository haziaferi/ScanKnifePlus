package com.haziaferi.scanknifeplus.scan.camera

import android.util.Log
import androidx.camera.core.CameraState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LiveData
import com.haziaferi.scanknifeplus.scanner.live.DelayScheduler

/**
 * The lifecycle the camera is bound to: it follows a screen's lifecycle but stays CREATED while the screen is paused, so CameraX closes the
 * camera at ON_PAUSE instead of ON_STOP and other apps (e.g. in split screen) get it back sooner, as OpenScan did. Not thread-safe: use it on the
 * main thread, which LifecycleRegistry and LiveData require, and let [scheduler] run its actions there too.
 */
internal class CameraLifecycle(scheduler: DelayScheduler, private val closeWaitMicros: Long) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private val resumeWait = CloseWait(scheduler, closeWaitMicros)

    override val lifecycle: Lifecycle get() = registry

    /**
     * Moves to the screen's state [screen], held at CREATED below RESUMED. A resume while [states], the bound camera's state, still reads OPEN
     * waits until it does not (at most [closeWaitMicros]): the main thread has not seen the pause's close yet, so CameraX would drop the
     * reopened camera's OPEN as a repeat (see ScanCameraController.bindUseCases).
     */
    fun follow(screen: Lifecycle.State, states: LiveData<CameraState>?) {
        resumeWait.cancel()
        val target = if (screen == Lifecycle.State.STARTED) Lifecycle.State.CREATED else screen
        if (target == Lifecycle.State.RESUMED && registry.currentState != target && states?.value?.type == CameraState.Type.OPEN) {
            resumeWait.start(states) { timedOut ->
                if (timedOut) Log.w(TAG, "Camera still open ${closeWaitMicros / 1_000} ms after the pause; resuming anyway")
                registry.currentState = Lifecycle.State.RESUMED
            }
            return
        }
        registry.currentState = target
    }

    /** Ends this lifecycle, which drops CameraX's binding to it; call it once its use cases are unbound. */
    fun destroy() {
        resumeWait.cancel()
        // LifecycleRegistry refuses to go from INITIALIZED straight to DESTROYED.
        if (registry.currentState == Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.DESTROYED
    }

    private companion object {
        const val TAG = "ScanCamera"
    }
}
