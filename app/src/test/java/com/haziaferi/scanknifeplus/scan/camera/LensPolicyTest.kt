package com.haziaferi.scanknifeplus.scan.camera

import com.haziaferi.scanknifeplus.scan.camera.CameraLens.BACK
import com.haziaferi.scanknifeplus.scan.camera.CameraLens.EXTERNAL
import com.haziaferi.scanknifeplus.scan.camera.CameraLens.FRONT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which camera opens, from the camera lists different kinds of device report, and which lenses get live detection. */
class LensPolicyTest {
    @Test
    fun `a back camera wins over every other`() {
        assertEquals(BACK, LensPolicy.choose(listOf(BACK, FRONT)))
        assertEquals(BACK, LensPolicy.choose(listOf(FRONT, BACK)))
        assertEquals(BACK, LensPolicy.choose(listOf(FRONT, BACK, BACK, EXTERNAL)))
        assertEquals(BACK, LensPolicy.choose(listOf(BACK)))
    }

    @Test
    fun `without a back camera an external one comes before the front one`() {
        assertEquals(EXTERNAL, LensPolicy.choose(listOf(FRONT, EXTERNAL)))
        assertEquals(EXTERNAL, LensPolicy.choose(listOf(EXTERNAL)))
    }

    @Test
    fun `a front camera only when it is the only kind`() {
        assertEquals(FRONT, LensPolicy.choose(listOf(FRONT)))
        assertEquals(FRONT, LensPolicy.choose(listOf(FRONT, FRONT)))
    }

    @Test
    fun `no camera at all opens nothing`() {
        assertNull(LensPolicy.choose(emptyList()))
    }

    @Test
    fun `only the back camera runs live detection`() {
        assertTrue(ScanCameraState(lens = BACK).liveDetection)
        assertFalse(ScanCameraState(lens = FRONT).liveDetection)
        assertFalse(ScanCameraState(lens = EXTERNAL).liveDetection)
        assertFalse("no camera open", ScanCameraState().liveDetection)
    }
}
