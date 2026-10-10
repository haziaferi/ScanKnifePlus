package com.haziaferi.scanknifeplus.scan.camera

import android.Manifest
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The controller's guards, where no camera is involved: missing permission and calls on an unbound camera. The camera itself is device-tested. */
@RunWith(RobolectricTestRunner::class)
class ScanCameraControllerTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val controller = ScanCameraController(app)
    private val states = ArrayList<ScanCameraState>()

    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    init {
        controller.stateListener = { states += it }
    }

    @After
    fun tearDown() = controller.release()

    @Test
    fun `binding without the camera permission fails cleanly`() {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        controller.bind(owner, null)
        assertEquals(CameraError.PERMISSION_DENIED, controller.state.error)
        assertEquals(CameraStatus.IDLE, controller.state.status)
        assertEquals(listOf(controller.state), states)
        assertFalse(controller.switchLens())
        assertNull(runBlocking { controller.takePicture() })
    }

    @Test
    fun `an unbound controller ignores every camera call`() {
        var torch: Boolean? = null
        controller.toggleTorch { torch = it }
        assertEquals(false, torch)
        controller.setZoom(2f)
        controller.finishZoom()
        assertFalse(controller.switchLens())
        assertNull(runBlocking { controller.takePicture() })
        controller.unbind()
        assertEquals(ScanCameraState(), controller.state)
        assertEquals(emptyList<ScanCameraState>(), states)
    }

    @Test
    fun `a released controller does not bind again`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        controller.release()
        controller.bind(owner, null)
        assertEquals(ScanCameraState(), controller.state)
    }
}
