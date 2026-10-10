// Ported from OpenScan lib/view/screens/live_scan/live_scan_screen.dart (_initializeCamera's timeout and single retry).
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scan.camera

import com.haziaferi.scanknifeplus.scanner.live.Cancellable
import com.haziaferi.scanknifeplus.scanner.live.DelayScheduler

/**
 * Times the camera's opening, as OpenScan's _initializeCamera did: an open that has not finished within [timeoutMicros] is abandoned and retried
 * once ([onRetry], which releases the camera and binds it again), and only a retry that also times out is reported ([onTimeout]). OpenScan
 * waited 1 s before its retry because it disposed the controller without waiting; CameraX serialises the release and the reopen, so the retry
 * starts at once.
 *
 * Not thread-safe: use it, and let [scheduler] run its actions, on one thread.
 */
class OpenWatchdog(
    private val scheduler: DelayScheduler,
    private val timeoutMicros: Long,
    private val onRetry: () -> Unit,
    private val onTimeout: () -> Unit,
) {
    private var timer: Cancellable? = null
    private var retried = false

    /** The camera is (still) opening: starts the timer unless it is already running. */
    fun opening() {
        if (timer != null) return
        timer = scheduler.schedule(timeoutMicros) {
            timer = null
            if (retried) {
                onTimeout()
            } else {
                retried = true
                onRetry()
            }
        }
    }

    /** Stops the timer but remembers a retry already spent, for the release that is part of the retry itself. */
    fun pause() {
        timer?.cancel()
        timer = null
    }

    /** The camera opened, closed, or was bound afresh: stops the timer and allows a retry again. */
    fun reset() {
        pause()
        retried = false
    }
}
