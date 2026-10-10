package com.haziaferi.scanknifeplus.scan.camera

import android.Manifest
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.haziaferi.scanknifeplus.scan.ScanFiles
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The scan camera on real hardware: streams, a full-resolution still, torch, zoom, lens switching, lifecycle and unbinding. */
@RunWith(AndroidJUnit4::class)
class ScanCameraControllerDeviceTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var controller: ScanCameraController
    private lateinit var owner: TestOwner
    private val readers = ArrayList<ImageReader>()

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private data class Frame(val width: Int, val height: Int, val rowStride: Int, val remaining: Int)

    private val frames = AtomicInteger()
    private val lastFrame = AtomicReference<Frame>()

    /** A preview surface the way a UI would supply one: an offscreen ImageReader in the camera's private format, drained as frames arrive. */
    private val surfaceProvider = Preview.SurfaceProvider { request ->
        val reader = ImageReader.newInstance(request.resolution.width, request.resolution.height, ImageFormat.PRIVATE, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        readers += reader
        request.provideSurface(reader.surface, ContextCompat.getMainExecutor(context)) { reader.close() }
    }

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun state(): ScanCameraState = main { controller.state }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, condition: (ScanCameraState) -> Boolean): ScanCameraState {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val s = state()
            if (condition(s)) return s
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $what; state ${state()}")
    }

    private fun waitForFrames(count: Int) {
        val target = frames.get() + count
        val end = System.currentTimeMillis() + 10_000
        while (frames.get() < target && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("expected $count more frames, got ${frames.get() - target + count}", frames.get() >= target)
    }

    private fun bind(lens: CameraLens = CameraLens.BACK): ScanCameraState {
        main { controller.bind(owner, surfaceProvider, lens) }
        return waitFor("the camera to open") { it.ready }
    }

    private fun takePicture(): File? = runBlocking { withContext(Dispatchers.Main) { controller.takePicture() } }

    private fun largestJpeg(cameraId: String): Dim {
        val manager = context.getSystemService(CameraManager::class.java)
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        return map.getOutputSizes(ImageFormat.JPEG).map { Dim(it.width, it.height) }.maxByOrNull { it.area }!!
    }

    @Before
    fun setUp() {
        ScanFiles.clearStaging(context)
        main {
            owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
            controller = ScanCameraController(context)
            controller.frameListener = { buffer, rowStride, width, height ->
                lastFrame.set(Frame(width, height, rowStride, buffer.remaining()))
                frames.incrementAndGet()
            }
        }
    }

    @After
    fun tearDown() {
        main {
            controller.release()
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
        ScanFiles.clearStaging(context)
    }

    @Test
    fun streamsFramesAndTakesAFullResolutionStill() {
        val s = bind()
        assertEquals(CameraLens.BACK, s.lens)
        val still = s.stillSize!!
        val analysis = s.analysisSize!!
        val largest = largestJpeg(s.cameraId!!)
        Log.i(TAG, "camera ${s.cameraId}: still $still (largest JPEG $largest), analysis $analysis, sensor ${s.sensorRotationDegrees} deg")
        assertEquals(largest, still)
        assertTrue("analysis $analysis has the still's shape", CameraSizes.sameShape(analysis, still))
        assertTrue("analysis $analysis within 1280x720", analysis.longEdge <= 1280 && analysis.shortEdge <= 720)

        waitForFrames(5)
        val frame = lastFrame.get()
        assertEquals(analysis.width, frame.width)
        assertEquals(analysis.height, frame.height)
        assertTrue(frame.rowStride >= frame.width)
        assertTrue("Y plane holds every row", frame.remaining >= frame.rowStride * (frame.height - 1) + frame.width)

        val file = takePicture()
        assertNotNull(file)
        assertEquals(ScanFiles.stagingDir(context), file!!.parentFile)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        Log.i(TAG, "still ${bounds.outWidth}x${bounds.outHeight}, EXIF orientation $orientation, ${file.length()} bytes")
        // CameraX leaves the pixels in sensor orientation and records the rotation in EXIF: a portrait still from a 90-degree back sensor.
        assertEquals(largest, Dim(bounds.outWidth, bounds.outHeight))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, orientation)
        assertFalse(state().capturing)

        // Analysis kept running through the capture.
        waitForFrames(5)

        val second = takePicture()
        assertNotNull(second)
        assertTrue("each shot gets its own file", second != file && file.exists() && second!!.exists())
    }

    @Test
    fun aSecondShutterPressWhileCapturingIsIgnored() {
        bind()
        val results = runBlocking {
            withContext(Dispatchers.Main) {
                val first = async { controller.takePicture() }
                val second = async { controller.takePicture() }
                listOf(first.await(), second.await())
            }
        }
        assertEquals(1, results.count { it != null })
    }

    @Test
    fun torchTurnsOnAndOffAndResetsOnLensSwitch() {
        val s = bind()
        assumeTrue("back camera has a flash unit", s.torchAvailable)
        var ok: Boolean? = null
        main { controller.toggleTorch { ok = it } }
        waitFor("torch on") { it.torchOn }
        assertEquals(true, ok)
        main { controller.setTorch(false) }
        waitFor("torch off") { !it.torchOn }
        main { controller.setTorch(true) }
        waitFor("torch on again") { it.torchOn }

        assumeTrue("device has a front camera", s.canSwitchLens)
        assertTrue(main { controller.switchLens() })
        val front = waitFor("the front camera") { it.ready && it.lens == CameraLens.FRONT }
        assertFalse(front.torchOn)
        assertTrue(main { controller.switchLens() })
        val back = waitFor("the back camera again") { it.ready && it.lens == CameraLens.BACK }
        assertFalse("torch stays off after rebinding", back.torchOn)
    }

    @Test
    fun zoomFollowsTheSliderAndLandsOnTheReleasedValue() {
        val s = bind()
        assumeTrue("camera can zoom", s.zoomSupported)
        Log.i(TAG, "zoom range ${s.minZoom}..${s.maxZoom}")
        val target = minOf(s.maxZoom, s.minZoom + 1.5f)
        // A drag: many updates inside a few throttle intervals.
        for (i in 1..30) {
            val r = s.minZoom + (target - s.minZoom) * i / 30
            main { controller.setZoom(r) }
            assertEquals(r, state().zoomRatio, 1e-4f)
            assertTrue(state().zooming)
            Thread.sleep(5)
        }
        main { controller.finishZoom() }
        assertFalse(state().zooming)
        waitFor("the camera's zoom to reach $target") { kotlin.math.abs(it.zoomRatio - target) < 1e-3f }
        main { controller.setZoom(s.maxZoom * 10) } // clamped
        assertEquals(s.maxZoom, state().zoomRatio, 1e-4f)
        main { controller.finishZoom() }
    }

    @Test
    fun frontCameraStillIsNotMirrored() {
        val s = bind(CameraLens.FRONT)
        assumeTrue("device has a front camera", s.lens == CameraLens.FRONT)
        val file = takePicture()!!
        val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        Log.i(TAG, "front still ${bounds.outWidth}x${bounds.outHeight} (largest ${largestJpeg(s.cameraId!!)}), EXIF orientation $orientation")
        assertTrue(
            "EXIF $orientation must not flip",
            orientation !in listOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_FLIP_VERTICAL,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE),
        )
        assertEquals(largestJpeg(s.cameraId!!), Dim(bounds.outWidth, bounds.outHeight))
    }

    @Test
    fun tapToFocusIsAccepted() {
        val s = bind()
        assertTrue(s.focusSupported)
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        main { controller.focusAt(0.3f, 0.6f, display) }
        Thread.sleep(1_500)
        assertTrue("focus metering still supported", state().focusSupported)
        assertTrue(state().ready)
    }

    @Test
    fun stoppingTheScreenClosesTheCameraAndStartingReopensItWithoutDelay() {
        val s = bind()
        if (s.torchAvailable) {
            main { controller.setTorch(true) }
            waitFor("torch on") { it.torchOn }
        }
        if (s.zoomSupported) {
            main { controller.setZoom(s.maxZoom); controller.finishZoom() }
            waitFor("zoomed") { it.zoomRatio == s.maxZoom }
        }
        main { owner.registry.currentState = Lifecycle.State.CREATED }
        waitFor("the camera to close") { it.status == CameraStatus.CLOSED }
        // Straight back, with no delay: CameraX serialises the close and the reopen itself (OpenScan waited 500 ms here).
        main { owner.registry.currentState = Lifecycle.State.RESUMED }
        val reopened = waitFor("the camera to reopen") { it.ready }
        assertFalse("torch is off after reopening", reopened.torchOn)
        assertEquals("zoom is reset after reopening", 1f, waitFor("zoom reset") { it.zoomRatio == 1f }.zoomRatio, 0f)
        waitForFrames(5)
        assertNotNull(takePicture())
    }

    @Test
    fun unbindStopsFramesAndResetsState() {
        bind()
        waitForFrames(3)
        main { controller.unbind() }
        assertEquals(ScanCameraState(), state())
        Thread.sleep(300)
        val after = frames.get()
        Thread.sleep(500)
        assertEquals("no frames after unbind", after, frames.get())
        assertEquals(null, takePicture())
        // And it can be bound again.
        bind()
        waitForFrames(3)
    }

    @Test
    fun destroyingTheOwnerUnbinds() {
        bind()
        main { owner.registry.currentState = Lifecycle.State.DESTROYED }
        assertEquals(ScanCameraState(), state())
    }

    private companion object {
        const val TAG = "ScanCameraTest"
    }
}
