package com.haziaferi.scanknifeplus.scan.camera

/**
 * Which camera the scanner opens, decided from the directions CameraX reports rather than from device models. A back camera always wins; a
 * device without one gets an external camera, plugged in on purpose and usually facing away from the user, and then the front camera. Only the
 * back camera runs live detection ([CameraLens.liveDetection]); the others are manual-shutter only.
 */
object LensPolicy {
    /** The lens to open among [available] (one entry per camera, duplicates allowed), or null when there is none. */
    fun choose(available: Collection<CameraLens>): CameraLens? = PREFERENCE.firstOrNull { it in available }

    private val PREFERENCE = listOf(CameraLens.BACK, CameraLens.EXTERNAL, CameraLens.FRONT)
}
