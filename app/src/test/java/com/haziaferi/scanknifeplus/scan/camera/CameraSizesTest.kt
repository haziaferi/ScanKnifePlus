package com.haziaferi.scanknifeplus.scan.camera

import androidx.camera.core.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Size rules for the scan camera's streams, plus the CameraX error mapping. Plain JVM. */
class CameraSizesTest {
    private fun dims(list: String) = list.split(", ").map { it.split('x').let { (w, h) -> Dim(w.toInt(), h.toInt()) } }

    // What CameraX 1.4.2 offers ImageCapture on the OnePlus 6T back camera (logged on the device): 4:3, 1:1, 16:9 and wider shapes. The sensor's
    // 4000x3000 JPEG is missing because CameraX's ExcludedSupportedSizesQuirk removes it on this device.
    private val oneplus6tStill = dims(
        "3264x2448, 3200x2400, 2592x1944, 2592x1940, 2304x1728, 2048x1536, 1920x1440, 1440x1080, 1280x960, 1024x768, 800x600, 640x480, " +
            "320x240, 1024x738, 352x288, 720x480, 1280x768, 800x480, 3456x3456, 2976x2976, 1080x1080, 4608x2592, 3840x2160, 2688x1512, 1920x1080, " +
            "1280x720, 4096x2160, 4608x2304, 2160x1080, 4608x2176, 4096x1940, 2280x1080, 4608x2112, 2340x1080",
    )

    // ...and to the YUV analysis stream, which does include 4000x3000.
    private val oneplus6tYuv = dims(
        "800x600, 640x480, 320x240, 1024x768, 1280x960, 1440x1080, 1920x1440, 2048x1536, 2304x1728, 2592x1940, 2592x1944, 3200x2400, 3264x2448, " +
            "4000x3000, 1024x738, 352x288, 176x144, 720x480, 800x480, 1280x768, 1080x1080, 2976x2976, 3456x3456, 1280x720, 1920x1080, 2688x1512, " +
            "3840x2160, 4608x2592, 4096x2160, 2160x1080, 4608x2304, 2280x1080, 4096x1940, 4608x2176, 2340x1080, 4608x2112",
    )

    private val sensor4x3 = Dim(4656, 3496)

    @Test
    fun `the still is the largest size of the sensor's shape, not a larger crop`() {
        val order = CameraSizes.stillPreference(oneplus6tStill.shuffled(), sensor4x3)
        assertEquals(Dim(3264, 2448), order.first())
        assertEquals(oneplus6tStill.size, order.size)
        // Larger-area crops (1:1 and 16:9 at 11.9 MP) only come after every 4:3 size.
        assertTrue(order.indexOf(Dim(3456, 3456)) > order.indexOf(Dim(320, 240)))
        assertTrue(order[order.indexOf(Dim(320, 240)) + 1] in setOf(Dim(3456, 3456), Dim(4608, 2592))) // equal areas
    }

    @Test
    fun `with no size of the sensor's shape, or no known shape, the still is the largest`() {
        assertEquals(Dim(3456, 3456), CameraSizes.stillPreference(listOf(Dim(1280, 720), Dim(3456, 3456)), sensor4x3).first())
        assertEquals(Dim(3456, 3456), CameraSizes.stillPreference(oneplus6tStill, null).first())
        assertTrue(CameraSizes.stillPreference(emptyList(), sensor4x3).isEmpty())
    }

    @Test
    fun `analysis takes the sensor's shape within OpenScan's 720p bound`() {
        val order = CameraSizes.streamPreference(oneplus6tYuv.shuffled(), sensor4x3, CameraSizes.ANALYSIS_BOUND)
        assertEquals(listOf(Dim(800, 600), Dim(640, 480), Dim(320, 240), Dim(1024, 768), Dim(1280, 960)), order.take(5))
        assertEquals(oneplus6tYuv.size, order.size) // nothing is dropped; other shapes follow as a last resort
        val fourByThree = oneplus6tYuv.count { CameraSizes.sameShape(it, sensor4x3) }
        assertTrue(order.take(fourByThree).all { CameraSizes.sameShape(it, sensor4x3) })
    }

    @Test
    fun `a 16 to 9 sensor gets OpenScan's own 1280x720 analysis stream`() {
        assertEquals(Dim(1280, 720), CameraSizes.streamPreference(oneplus6tYuv, Dim(1920, 1080), CameraSizes.ANALYSIS_BOUND).first())
    }

    @Test
    fun `the preview takes the sensor's shape up to 1080p`() {
        assertEquals(Dim(1440, 1080), CameraSizes.streamPreference(oneplus6tYuv, sensor4x3, CameraSizes.PREVIEW_BOUND).first())
    }

    @Test
    fun `with nothing of the right shape within the bound the smallest larger one comes first`() {
        val order = CameraSizes.streamPreference(listOf(Dim(1920, 1080), Dim(4000, 3000), Dim(1920, 1440)), sensor4x3, CameraSizes.ANALYSIS_BOUND)
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
