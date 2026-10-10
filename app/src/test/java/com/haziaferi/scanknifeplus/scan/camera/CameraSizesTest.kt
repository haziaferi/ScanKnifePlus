package com.haziaferi.scanknifeplus.scan.camera

import androidx.camera.core.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Size rules for the scan camera's streams on a representative size list, plus the CameraX error mapping. Plain JVM. */
class CameraSizesTest {
    // A typical back camera's output sizes: 4:3, 16:9, 18:9 and 1:1 shapes.
    private val sizes = listOf(
        Dim(4608, 3456), Dim(4608, 2592), Dim(4608, 2240), Dim(3456, 3456), Dim(4000, 3000), Dim(3840, 2160), Dim(1920, 1440), Dim(1920, 1080),
        Dim(1440, 1080), Dim(1280, 960), Dim(1280, 720), Dim(960, 720), Dim(720, 480), Dim(640, 480), Dim(320, 240),
    )

    @Test
    fun `the still is the largest size, whatever its shape`() {
        assertEquals(Dim(4608, 3456), CameraSizes.largest(sizes))
        assertEquals(Dim(3456, 3456), CameraSizes.largest(listOf(Dim(4000, 2000), Dim(3456, 3456))))
        assertNull(CameraSizes.largest(emptyList()))
        assertEquals(Dim(4608, 3456), CameraSizes.stillPreference(sizes.shuffled()).first())
    }

    @Test
    fun `analysis takes the still's shape within OpenScan's 720p bound`() {
        val order = CameraSizes.streamPreference(sizes, Dim(4608, 3456), CameraSizes.ANALYSIS_BOUND)
        assertEquals(listOf(Dim(960, 720), Dim(640, 480), Dim(320, 240), Dim(1280, 960)), order.take(4))
        assertEquals(sizes.size, order.size) // nothing is dropped; other shapes follow as a last resort
        assertTrue(order.drop(8).none { CameraSizes.sameShape(it, Dim(4, 3)) })
    }

    @Test
    fun `a 16 to 9 still gets OpenScan's own 1280x720 analysis stream`() {
        val order = CameraSizes.streamPreference(sizes, Dim(4608, 2592), CameraSizes.ANALYSIS_BOUND)
        assertEquals(Dim(1280, 720), order.first())
    }

    @Test
    fun `the preview takes the still's shape up to 1080p`() {
        assertEquals(Dim(1440, 1080), CameraSizes.streamPreference(sizes, Dim(4608, 3456), CameraSizes.PREVIEW_BOUND).first())
    }

    @Test
    fun `with nothing of the still's shape within the bound the smallest larger one comes first`() {
        val order = CameraSizes.streamPreference(listOf(Dim(1920, 1080), Dim(4000, 3000), Dim(1920, 1440)), Dim(4000, 3000), CameraSizes.ANALYSIS_BOUND)
        assertEquals(listOf(Dim(1920, 1440), Dim(4000, 3000), Dim(1920, 1080)), order)
    }

    @Test
    fun `shape comparison tolerates rounding and ignores orientation`() {
        assertTrue(CameraSizes.sameShape(Dim(4656, 3496), Dim(4, 3)))
        assertTrue(CameraSizes.sameShape(Dim(720, 960), Dim(960, 720)))
        assertFalse(CameraSizes.sameShape(Dim(1280, 720), Dim(4, 3)))
        assertFalse(CameraSizes.sameShape(Dim(4608, 2240), Dim(16, 9)))
    }

    @Test
    fun `CameraX errors map to recoverable and fatal kinds`() {
        assertEquals(CameraError.IN_USE, CameraError.fromCameraX(CameraState.ERROR_CAMERA_IN_USE))
        assertEquals(CameraError.IN_USE, CameraError.fromCameraX(CameraState.ERROR_MAX_CAMERAS_IN_USE))
        assertEquals(CameraError.RECOVERABLE, CameraError.fromCameraX(CameraState.ERROR_OTHER_RECOVERABLE_ERROR))
        assertEquals(CameraError.DISABLED, CameraError.fromCameraX(CameraState.ERROR_CAMERA_DISABLED))
        assertEquals(CameraError.DISABLED, CameraError.fromCameraX(CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED))
        assertEquals(CameraError.FATAL, CameraError.fromCameraX(CameraState.ERROR_CAMERA_FATAL_ERROR))
        assertEquals(CameraError.FATAL, CameraError.fromCameraX(CameraState.ERROR_STREAM_CONFIG))
        assertTrue(CameraError.IN_USE.recoverable)
        assertFalse(CameraError.FATAL.recoverable)
    }
}
