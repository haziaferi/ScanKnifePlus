package com.haziaferi.scanknifeplus.scan.camera

import androidx.camera.core.CameraState

/** Which way the camera faces. The live pipeline needs it: only the back camera's frames map onto its still for the auto-crop quad. */
enum class CameraLens { BACK, FRONT }

/** Where the camera is in its life: not bound, opening (or waiting to reopen), streaming, or closed while the screen is stopped. */
enum class CameraStatus { IDLE, OPENING, READY, CLOSED }

/** Why the camera is not (or not yet) usable. Kinds marked recoverable clear by themselves when CameraX manages to reopen the camera. */
enum class CameraError(val recoverable: Boolean) {
    /** The CAMERA permission is not granted; nothing was opened. */
    PERMISSION_DENIED(false),

    /** The device has no camera CameraX can use. */
    NO_CAMERA(false),

    /** CameraX could not initialise. */
    INIT_FAILED(false),

    /** The use cases could not be bound (no supported stream combination, or the screen was already destroyed). */
    BIND_FAILED(false),

    /** The camera did not open within [ScanCameraController.OPEN_TIMEOUT_MS]; CameraX keeps trying, and the error clears if it opens. */
    OPEN_TIMEOUT(true),

    /** Another app holds the camera, or too many cameras are open; CameraX reopens it when it is released. */
    IN_USE(true),

    /** A transient camera error that CameraX is retrying. */
    RECOVERABLE(true),

    /** The camera is disabled by device policy or by Do Not Disturb. */
    DISABLED(false),

    /** A fatal camera or stream configuration error; binding again may help, retrying in place will not. */
    FATAL(false);

    companion object {
        /** Maps a CameraX [CameraState.StateError] code; codes this version does not know count as fatal. */
        fun fromCameraX(code: Int): CameraError = when (code) {
            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> IN_USE
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR -> RECOVERABLE
            CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> DISABLED
            else -> FATAL
        }
    }
}

/**
 * A snapshot of the scan camera for whatever UI is built on top, in plain values (no CameraX, LiveData or Compose types). [zoomRatio] is the
 * requested ratio while a slider drags ([zooming], during which live detection pauses as in OpenScan) and the camera's own ratio otherwise.
 * Sizes are in sensor orientation; [sensorRotationDegrees] turns them upright for a portrait screen.
 */
data class ScanCameraState(
    val status: CameraStatus = CameraStatus.IDLE,
    val error: CameraError? = null,
    val lens: CameraLens? = null,
    val canSwitchLens: Boolean = false,
    val torchAvailable: Boolean = false,
    val torchOn: Boolean = false,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val zoomRatio: Float = 1f,
    val zooming: Boolean = false,
    val focusSupported: Boolean = false,
    val capturing: Boolean = false,
    val sensorRotationDegrees: Int = 0,
    val stillSize: Dim? = null,
    val analysisSize: Dim? = null,
    val cameraId: String? = null,
) {
    /** True when frames are flowing and a picture can be taken. */
    val ready: Boolean get() = status == CameraStatus.READY

    /** Whether a zoom control has any range to offer. */
    val zoomSupported: Boolean get() = maxZoom > minZoom
}
