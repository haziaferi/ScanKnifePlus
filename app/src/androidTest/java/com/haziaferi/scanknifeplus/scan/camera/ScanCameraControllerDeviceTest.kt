package com.haziaferi.scanknifeplus.scan.camera

import android.Manifest
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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

/** The scan camera on real hardware: streams, a full-resolution still, torch, zoom, the lens policy, lifecycle (pause and stop) and unbinding. */
@OptIn(DelicateCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ScanCameraControllerDeviceTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var controller: ScanCameraController
    private lateinit var owner: TestOwner
    private val readers = ArrayList<ImageReader>()

    /** Stands in for a scan session's own staging folder. */
    private lateinit var shotDir: File

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private data class Frame(val width: Int, val height: Int, val rowStride: Int, val remaining: Int, val rotation: Int, val lens: CameraLens)

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

    private fun bind(): ScanCameraState {
        main { controller.bind(owner, surfaceProvider) }
        return waitFor("the camera to open") { it.ready }
    }

    private fun takePicture(): File? = runBlocking { withContext(Dispatchers.Main) { controller.takePicture(shotDir) } }

    /**
     * The still the controller should pick, worked out independently from camera2: the largest JPEG size with the sensor's active-array shape,
     * minus the sizes CameraX's ExcludedSupportedSizesQuirk withholds on this device (OnePlus 6T camera 0: 4160x3120 and 4000x3000; the same
     * in CameraX 1.4.2 and 1.5.3).
     */
    private fun expectedStill(cameraId: String): Dim {
        val chars = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
        val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!.let { Dim(it.width(), it.height()) }
        val jpeg = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.JPEG).map { Dim(it.width, it.height) }
        val excluded = if (Build.BRAND.equals("OnePlus", true) && Build.DEVICE.equals("OnePlus6T", true) && cameraId == "0") {
            setOf(Dim(4160, 3120), Dim(4000, 3000))
        } else {
            emptySet()
        }
        Log.i(TAG, "camera $cameraId: active array $active, largest JPEG ${jpeg.maxByOrNull { it.area }}, excluded by CameraX $excluded")
        return jpeg.filter { CameraSizes.sameShape(it, active) && it !in excluded }.maxByOrNull { it.area }!!
    }

    @Before
    fun setUp() {
        shotDir = ScanFiles.newFolder(ScanFiles.stagingDir(context), "test-")!!
        main {
            owner = TestOwner().apply { registry.currentState = Lifecycle.State.RESUMED }
            controller = ScanCameraController(context)
            controller.frameListener = { buffer, rowStride, width, height, rotation, lens ->
                lastFrame.set(Frame(width, height, rowStride, buffer.remaining(), rotation, lens))
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
        shotDir.deleteRecursively()
    }

    @Test
    fun streamsFramesAndTakesAFullResolutionStill() {
        val s = bind()
        assertEquals(CameraLens.BACK, s.lens)
        assertTrue(s.liveDetection)
        val still = s.stillSize!!
        val analysis = s.analysisSize!!
        val expected = expectedStill(s.cameraId!!)
        Log.i(TAG, "camera ${s.cameraId}: still $still (expected $expected), analysis $analysis, sensor ${s.sensorRotationDegrees} deg")
        assertEquals(expected, still)
        assertTrue("analysis $analysis has the still's shape", CameraSizes.sameShape(analysis, still))
        assertTrue("analysis $analysis within 1280x720", analysis.longEdge <= 1280 && analysis.shortEdge <= 720)

        waitForFrames(5)
        val frame = lastFrame.get()
        assertEquals(analysis.width, frame.width)
        assertEquals(analysis.height, frame.height)
        assertTrue(frame.rowStride >= frame.width)
        assertTrue("Y plane holds every row", frame.remaining >= frame.rowStride * (frame.height - 1) + frame.width)
        // The frame's rotation is the sensor's mount, and it comes with the lens the frame is from.
        assertEquals(s.sensorRotationDegrees, frame.rotation)
        assertEquals(CameraLens.BACK, frame.lens)

        val file = takePicture()
        assertNotNull(file)
        assertEquals(shotDir, file!!.parentFile)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        Log.i(TAG, "still ${bounds.outWidth}x${bounds.outHeight}, EXIF orientation $orientation, ${file.length()} bytes")
        // CameraX leaves the pixels in sensor orientation and records the rotation in EXIF: a portrait still from a 90-degree back sensor.
        assertEquals(expected, Dim(bounds.outWidth, bounds.outHeight))
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
                val first = async { controller.takePicture(shotDir) }
                val second = async { controller.takePicture(shotDir) }
                listOf(first.await(), second.await())
            }
        }
        assertEquals(1, results.count { it != null })
    }

    @Test
    fun aPictureIsRefusedUntilTheCameraIsOpen() {
        // Press the shutter the moment the use cases are bound to a camera that CameraX has not opened yet (CameraX would queue that capture).
        val result = CompletableDeferred<File?>()
        main {
            controller.stateListener = { s ->
                if (s.lens != null && s.status == CameraStatus.OPENING && result.isActive) {
                    controller.stateListener = null
                    GlobalScope.launch(Dispatchers.Main.immediate) { result.complete(controller.takePicture(shotDir)) }
                }
            }
            controller.bind(owner, surfaceProvider)
        }
        assertEquals(null, runBlocking { withTimeout(10_000) { result.await() } })
        assertFalse("a refused shot leaves nothing in flight", state().capturing)
        waitFor("the camera to open") { it.ready }
        assertNotNull(takePicture())
    }

    @Test
    fun cancellingATakeKeepsTheShutterBlockedUntilCameraXFinishes() {
        val s = bind()
        val job = runBlocking { withContext(Dispatchers.Main) { GlobalScope.launch(Dispatchers.Main) { controller.takePicture(shotDir) } } }
        waitFor("the capture to start") { it.capturing }
        Thread.sleep(100) // past the folder check, into CameraX's capture (which takes far longer)
        runBlocking { job.cancelAndJoin() }
        assertTrue("still capturing after the caller was cancelled", state().capturing)
        assertEquals("no second shot mid-capture", null, takePicture())
        waitFor("CameraX to finish the cancelled shot") { !it.capturing }
        assertEquals(s.lens, state().lens)
        // The shot that arrived after the cancellation is deleted (on an IO thread).
        val end = System.currentTimeMillis() + 5_000
        while (shotDir.listFiles().orEmpty().isNotEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(emptyList<File>(), shotDir.listFiles().orEmpty().toList())
        assertNotNull("the shutter works again", takePicture())
    }

    @Test
    fun stoppingTheScreenMidZoomClearsTheGesture() {
        val s = bind()
        assumeTrue("camera can zoom", s.zoomSupported)
        main { controller.setZoom(s.maxZoom / 2) }
        assertTrue(state().zooming)
        main { owner.registry.currentState = Lifecycle.State.CREATED } // no finishZoom: the screen just went away
        waitFor("the camera to close") { it.status == CameraStatus.CLOSED }
        assertFalse(state().zooming)
        main { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitFor("the camera to reopen with the zoom reset") { it.ready && it.zoomRatio == 1f && !it.zooming }
    }

    @Test
    fun torchTurnsOnAndOff() {
        val s = bind()
        assumeTrue("back camera has a flash unit", s.torchAvailable)
        var ok: Boolean? = null
        main { controller.toggleTorch { ok = it } }
        waitFor("torch on") { it.torchOn }
        val end = System.currentTimeMillis() + 5_000
        while (main { ok } == null && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(true, main { ok })
        main { controller.setTorch(false) }
        waitFor("torch off") { !it.torchOn }
        main { controller.setTorch(true) }
        waitFor("torch on again") { it.torchOn }
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
    fun aDeviceWithOnlyAFrontCameraOpensItWithoutLiveDetection() {
        // Poses as a front-only device: the policy sees only this phone's front cameras.
        main { controller.lensPolicy = { lenses -> LensPolicy.choose(lenses.filter { it == CameraLens.FRONT }) } }
        val s = bind()
        assumeTrue("device has a front camera", s.lens == CameraLens.FRONT)
        assertFalse("manual shutter only", s.liveDetection)
        waitForFrames(3)
        assertEquals(CameraLens.FRONT, lastFrame.get().lens)
        val file = takePicture()!!
        val orientation = ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        Log.i(TAG, "front still ${bounds.outWidth}x${bounds.outHeight} (expected ${expectedStill(s.cameraId!!)}), EXIF orientation $orientation")
        assertTrue(
            "EXIF $orientation must not flip",
            orientation !in listOf(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, ExifInterface.ORIENTATION_FLIP_VERTICAL,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE),
        )
        assertEquals(expectedStill(s.cameraId!!), Dim(bounds.outWidth, bounds.outHeight))
    }

    @Test
    fun aDeviceWithNoUsableCameraReportsNoCamera() {
        main {
            controller.lensPolicy = { LensPolicy.choose(emptyList()) }
            controller.bind(owner, surfaceProvider)
        }
        waitFor("the no-camera error") { it.error == CameraError.NO_CAMERA }
        assertEquals(null, takePicture())
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

    /** Whether camera2 reports [cameraId] free for other apps, kept up to date on the main thread. */
    private fun watchAvailability(cameraId: String): Pair<AtomicReference<Boolean>, CameraManager.AvailabilityCallback> {
        val available = AtomicReference<Boolean>()
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(id: String) {
                if (id == cameraId) available.set(true)
            }

            override fun onCameraUnavailable(id: String) {
                if (id == cameraId) available.set(false)
            }
        }
        context.getSystemService(CameraManager::class.java).registerAvailabilityCallback(callback, Handler(Looper.getMainLooper()))
        return available to callback
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(5)
        assertTrue("timed out waiting for $what", condition())
    }

    @Test
    fun pausingTheScreenReleasesTheCameraToOtherAppsAndResumingReopensIt() {
        val s = bind()
        val (available, callback) = watchAvailability(s.cameraId!!)
        try {
            waitUntil("camera2 to report the camera in use") { available.get() == false }
            repeat(PAUSES) { i ->
                waitForFrames(3)
                val paused = System.nanoTime()
                main { owner.registry.currentState = Lifecycle.State.STARTED }
                waitUntil("the camera to be free for other apps") { available.get() == true }
                val released = (System.nanoTime() - paused) / 1_000_000
                assertEquals(CameraStatus.CLOSED, waitFor("the camera to close") { it.status == CameraStatus.CLOSED }.status)
                Thread.sleep(300)
                val before = frames.get()
                Thread.sleep(300)
                assertEquals("no frames while paused", before, frames.get())

                val resumed = System.nanoTime()
                main { owner.registry.currentState = Lifecycle.State.RESUMED }
                waitFor("the camera to reopen") { it.ready }
                val reopened = (System.nanoTime() - resumed) / 1_000_000
                waitForFrames(1)
                val firstFrame = (System.nanoTime() - resumed) / 1_000_000
                Log.i(TAG, "pause $i: camera free after $released ms; resume: open after $reopened ms, first frame after $firstFrame ms")
            }
            assertNotNull(takePicture())
        } finally {
            context.getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(callback)
        }
    }

    @Test
    fun aScreenThatIsNotResumedKeepsTheCameraClosed() {
        main {
            owner.registry.currentState = Lifecycle.State.STARTED
            controller.bind(owner, surfaceProvider)
        }
        waitFor("the use cases to be bound") { it.lens != null }
        Thread.sleep(1_000)
        assertEquals(0, frames.get())
        assertFalse(state().ready)
        main { owner.registry.currentState = Lifecycle.State.RESUMED }
        waitFor("the camera to open") { it.ready }
        waitForFrames(3)
    }

    @Test
    fun aQuickPauseAndResumeWhileTheMainThreadIsBusyReopensTheCamera() {
        bind()
        repeat(REOPENS) { i ->
            waitForFrames(3)
            val start = System.nanoTime()
            // A focus change in multi-window: paused and resumed before the main thread hears of the camera closing, then busy for a moment.
            main {
                owner.registry.currentState = Lifecycle.State.STARTED
                owner.registry.currentState = Lifecycle.State.RESUMED
                Thread.sleep(BUSY_MS)
            }
            waitForFrames(3)
            main {} // delivers the camera states CameraX posted before those frames
            assertTrue("round $i: streaming, but state ${state()}", state().ready)
            Log.i(TAG, "quick pause $i: streaming again ${(System.nanoTime() - start) / 1_000_000 - BUSY_MS} ms after the busy main thread")
        }
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

    /**
     * Reopens the streaming camera through [rebind] while the main thread is busy for a moment once the new binding is made, as it is while a
     * screen is recreated, so CameraX posts the camera's whole close and reopen before the main thread receives any of it. Frames from the new
     * binding then prove the camera open, and the controller must say so too: a lost OPEN would leave it at OPENING until the watchdog's retry.
     */
    private fun reopenWhileTheMainThreadIsBusy(rebind: () -> Unit) {
        repeat(REOPENS) { i ->
            if (!state().ready) bind()
            waitForFrames(3)
            main {
                rebind()
                controller.stateListener = { s ->
                    if (s.lens != null) {
                        controller.stateListener = null
                        Thread.sleep(BUSY_MS)
                    }
                }
            }
            waitForFrames(3)
            main {} // delivers the camera states CameraX posted before those frames
            assertTrue("round $i: streaming, but state ${state()}", state().ready)
        }
    }

    @Test
    fun rebindingTheStreamingCameraReportsItOpen() = reopenWhileTheMainThreadIsBusy { controller.bind(owner, surfaceProvider) }

    @Test
    fun aNewControllerReportsOpenTheCameraTheOldOneJustReleased() = reopenWhileTheMainThreadIsBusy {
        val listener = controller.frameListener
        controller.release()
        controller = ScanCameraController(context)
        controller.frameListener = listener
        controller.bind(owner, surfaceProvider)
    }

    /**
     * Keeps the back camera open through a binding of its own to another started owner, as a second camera user in the process would, and
     * returns its release.
     */
    private fun holdTheBackCameraOpen(): () -> Unit {
        val provider = ProcessCameraProvider.getInstance(context).get()
        val holder = TestOwner()
        val analysis = ImageAnalysis.Builder().build()
        val camera = main {
            holder.registry.currentState = Lifecycle.State.RESUMED
            analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { it.close() }
            provider.bindToLifecycle(holder, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
        }
        val end = System.currentTimeMillis() + 10_000
        while (main { camera.cameraInfo.cameraState.value?.type } != CameraState.Type.OPEN && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(CameraState.Type.OPEN, main { camera.cameraInfo.cameraState.value?.type })
        return {
            main {
                provider.unbind(analysis)
                holder.registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    @Test
    fun aCameraHeldOpenElsewhereIsBoundOnceTheCloseWaitRunsOut() {
        val releaseHolder = holdTheBackCameraOpen()
        try {
            val start = System.currentTimeMillis()
            main { controller.bind(owner, surfaceProvider) }
            Thread.sleep(ScanCameraController.CLOSE_WAIT_MS / 2)
            assertEquals("still waiting for the camera to leave OPEN", null, state().lens)
            val bound = waitFor("the bind after the close wait") { it.lens != null }
            val waited = System.currentTimeMillis() - start
            Log.i(TAG, "bound after $waited ms: $bound")
            assertTrue("bound after $waited ms", waited >= ScanCameraController.CLOSE_WAIT_MS)
            waitFor("the camera to open") { it.ready }
        } finally {
            releaseHolder()
        }
    }

    @Test
    fun unbindingDuringTheCloseWaitBindsNothing() {
        val releaseHolder = holdTheBackCameraOpen()
        try {
            val published = ArrayList<ScanCameraState>()
            main { controller.bind(owner, surfaceProvider) }
            Thread.sleep(ScanCameraController.CLOSE_WAIT_MS / 4)
            main {
                assertEquals("still waiting for the camera to leave OPEN", null, controller.state.lens)
                controller.unbind()
                controller.stateListener = { published += it }
            }
            Thread.sleep(ScanCameraController.CLOSE_WAIT_MS * 2)
            assertEquals(ScanCameraState(), state())
            assertEquals("no state after the unbind", emptyList<ScanCameraState>(), main { published.toList() })
        } finally {
            releaseHolder()
        }
    }

    @Test
    fun destroyingTheOwnerUnbinds() {
        bind()
        main { owner.registry.currentState = Lifecycle.State.DESTROYED }
        assertEquals(ScanCameraState(), state())
    }

    private companion object {
        const val TAG = "ScanCameraTest"
        const val REOPENS = 3
        const val PAUSES = 3

        /** Longer than the camera takes to close and reopen. */
        const val BUSY_MS = 1_500L
    }
}
