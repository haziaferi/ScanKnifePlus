// Ported from OpenScan lib/view/screens/live_scan/live_scan_screen.dart (the camera handling of the live scan screen).
// Copyright (c) 2021, Vijay T S and Vikram H, BSD-3-Clause. See NOTICE.md.
package com.haziaferi.scanknifeplus.scan.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.TorchState
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionFilter
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import com.haziaferi.scanknifeplus.scan.CameraPermission
import com.haziaferi.scanknifeplus.scan.ScanFiles
import com.haziaferi.scanknifeplus.scanner.live.Cancellable
import com.haziaferi.scanknifeplus.scanner.live.DelayScheduler
import com.haziaferi.scanknifeplus.scanner.live.ZoomThrottle
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Receives each analysis frame's Y (luma) plane in sensor orientation: [yPlane] holds [height] rows of [rowStride] bytes (the last row may be
 * only [width] long), pixel stride 1. Called on the analysis thread; the buffer is only valid during the call, so copy what you keep.
 */
typealias FrameListener = (yPlane: ByteBuffer, rowStride: Int, width: Int, height: Int) -> Unit

/**
 * The document scanner's camera, without any UI: CameraX preview (onto a surface the UI supplies), a low-resolution YUV analysis stream for live
 * detection, and full-resolution stills written to [ScanFiles.stagingDir]. Ports the camera side of OpenScan's live scan screen; see the
 * differences listed below. Every method must be called on the main thread; state changes reach [stateListener] there too. Nothing here throws:
 * failures show up as [ScanCameraState.error], a null picture or a false result.
 *
 * Differences from OpenScan, all deliberate:
 *  - Stills are the largest size of the sensor's shape that CameraX offers (the sensor's whole field of view), taken with
 *    [ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY], instead of ResolutionPreset.high's 720p. The preview and analysis streams take the same shape
 *    (see [CameraSizes]) so the detected quad lands on the same field of view.
 *  - The still flash is always off; the torch is the only light, and it is off whenever the camera is (re)opened.
 *  - Lifecycle: the camera is bound to the screen's [LifecycleOwner], so CameraX closes it when the screen stops and reopens it when it starts.
 *    OpenScan disposed its controller by hand on pause and reopened after a 500 ms delay, because a new camera2 session could race the old one's
 *    close. CameraX serialises close and reopen on its own camera thread (the same device object is reused), so no delay is needed. OpenScan
 *    also released on `inactive` (onPause); CameraX keeps the camera while the screen is paused but visible, e.g. in multi-window.
 *  - The analysis stream keeps running while a picture is taken. OpenScan stopped its image stream around takePicture; CameraX runs analysis and
 *    capture as separate streams of one session, so nothing has to stop (the session's stream combination is configured for both).
 *  - OpenScan's open timeout plus one retry becomes CameraX's own open retry plus an [OPEN_TIMEOUT_MS] watchdog that reports
 *    [CameraError.OPEN_TIMEOUT] without giving up.
 *  - Front-camera stills are not mirrored (EXIF says so explicitly), so a document photographed with it reads the right way round.
 *
 * Stills keep the sensor's pixel layout and carry the rotation in EXIF (CameraX's default when nothing is cropped); ImageCodec applies it.
 */
class ScanCameraController(context: Context) {
    companion object {
        private const val TAG = "ScanCamera"

        /** How long the camera may take to open before [CameraError.OPEN_TIMEOUT] is reported (OpenScan's initialize timeout). */
        const val OPEN_TIMEOUT_MS = 5_000L

        /** Quality of the staged still; CameraX's default when not maximizing quality, which is what OpenScan's camera plugin used. */
        const val STILL_JPEG_QUALITY = 95

        private const val FLAGS_AF_AE = FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "ScanCameraAnalysis") }

    /** Current state; replaced, never mutated. */
    var state = ScanCameraState()
        private set

    /** Called on the main thread with every new [state]. */
    var stateListener: ((ScanCameraState) -> Unit)? = null

    /** Receives every analysis frame; see [FrameListener]. May be swapped at any time, from any thread. */
    @Volatile
    var frameListener: FrameListener? = null

    /**
     * Rotation of the stills (a Surface.ROTATION_* value). ROTATION_0, the default, tags stills upright for a portrait screen, which is what the
     * live overlay and the quad crop assume; OpenScan locked the whole app to portrait.
     */
    var targetRotation: Int = Surface.ROTATION_0
        set(value) {
            field = value
            imageCapture?.targetRotation = value
            imageAnalysis?.targetRotation = value
            preview?.targetRotation = value
        }

    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null
    private var requestedLens = CameraLens.BACK
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var generation = 0
    private var released = false
    private var openTimeout: Runnable? = null

    private val zoomThrottle = ZoomThrottle(
        apply = { ratio -> camera?.cameraControl?.setZoomRatio(ratio)?.let { ignoreSuperseded(it, "zoom") } },
        scheduler = DelayScheduler { delayMicros, action ->
            val runnable = Runnable(action)
            mainHandler.postDelayed(runnable, delayMicros / 1_000)
            Cancellable { mainHandler.removeCallbacks(runnable) }
        },
    )

    private val cameraStateObserver = Observer<CameraState> { onCameraState(it) }
    private val torchObserver = Observer<Int> { update { copy(torchOn = it == TorchState.ON) } }
    private val zoomObserver = Observer<ZoomState> { onZoomState(it) }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) = unbind()
    }

    /**
     * Opens the camera for [owner]'s lifecycle, facing [lens] (or the other way if the device has no such camera, as OpenScan fell back to its
     * first camera). [surfaceProvider] receives the preview; pass null to run without one (analysis and stills still work). Replaces any
     * earlier binding. Fails cleanly, with [CameraError.PERMISSION_DENIED], if the CAMERA permission is missing: ask for it before calling.
     */
    @MainThread
    fun bind(owner: LifecycleOwner, surfaceProvider: Preview.SurfaceProvider?, lens: CameraLens = CameraLens.BACK) {
        if (released) return
        unbind()
        if (!CameraPermission.isGranted(appContext)) {
            update { ScanCameraState(error = CameraError.PERMISSION_DENIED) }
            return
        }
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        this.owner = owner
        this.surfaceProvider = surfaceProvider
        requestedLens = lens
        owner.lifecycle.addObserver(lifecycleObserver)
        update { ScanCameraState(status = CameraStatus.OPENING) }

        val token = generation
        val future = try {
            ProcessCameraProvider.getInstance(appContext)
        } catch (e: Exception) {
            fail(CameraError.INIT_FAILED, e)
            return
        }
        future.addListener({
            if (token != generation || released) return@addListener
            val p = try {
                future.get()
            } catch (e: Exception) {
                fail(CameraError.INIT_FAILED, e)
                return@addListener
            }
            provider = p
            bindUseCases(p)
        }, mainExecutor)
    }

    /** Replaces the preview's surface provider (null detaches the preview output). */
    @MainThread
    fun setSurfaceProvider(surfaceProvider: Preview.SurfaceProvider?) {
        this.surfaceProvider = surfaceProvider
        preview?.setSurfaceProvider(surfaceProvider)
    }

    /** Releases the camera and resets the state to idle; [bind] may be called again later. */
    @MainThread
    fun unbind() {
        generation++
        cancelOpenTimeout()
        zoomThrottle.cancel()
        detachCamera()
        val p = provider
        val useCases = listOfNotNull(preview, imageCapture, imageAnalysis)
        if (p != null && useCases.isNotEmpty()) {
            try {
                p.unbind(*useCases.toTypedArray())
            } catch (e: Exception) {
                Log.w(TAG, "Unbind failed", e)
            }
        }
        imageAnalysis?.clearAnalyzer()
        preview = null
        imageCapture = null
        imageAnalysis = null
        owner?.lifecycle?.removeObserver(lifecycleObserver)
        owner = null
        if (state != ScanCameraState()) update { ScanCameraState() }
    }

    /** [unbind]s and stops the analysis thread; the controller cannot be used afterwards. */
    @MainThread
    fun release() {
        unbind()
        released = true
        frameListener = null
        stateListener = null
        analysisExecutor.shutdown()
    }

    /**
     * Turns to the other camera, as OpenScan's _switchCamera: the torch goes off and the zoom resets with the old camera. Does nothing, returning
     * false, while a picture is being taken or when the device has only one direction.
     */
    @MainThread
    fun switchLens(): Boolean {
        val s = state
        val owner = owner ?: return false
        if (s.capturing || !s.canSwitchLens || s.lens == null) return false
        bind(owner, surfaceProvider, if (s.lens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK)
        return true
    }

    /**
     * Takes a full-resolution still and writes it to a new file in [ScanFiles.stagingDir]. Returns the file as soon as it is written: processing
     * the page is the caller's business, so the shutter never waits for it. Returns null if the camera is not bound, a picture is already being
     * taken (OpenScan ignored a second press), or the capture fails. If the caller is cancelled first, the file is deleted when it arrives.
     */
    @MainThread
    suspend fun takePicture(): File? {
        val capture = imageCapture ?: return null
        if (state.capturing) return null
        val file = File(ScanFiles.stagingDir(appContext), "shot-${UUID.randomUUID()}.jpg")
        val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = false }
        val options = ImageCapture.OutputFileOptions.Builder(file).setMetadata(metadata).build()
        update { copy(capturing = true) }
        return try {
            suspendCancellableCoroutine { cont ->
                try {
                    capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            cont.resume(file) { file.delete() }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.w(TAG, "Capture failed", exception)
                            file.delete()
                            cont.resume(null)
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "Capture failed", e)
                    cont.resume(null)
                }
            }
        } finally {
            update { copy(capturing = false) }
        }
    }

    /** Turns the torch on or off ([onResult] gets false if the camera refused, like OpenScan's "torch unavailable"); state follows CameraX. */
    @MainThread
    fun setTorch(on: Boolean, onResult: (Boolean) -> Unit = {}) {
        val control = camera?.cameraControl
        if (control == null || !state.torchAvailable) {
            onResult(false)
            return
        }
        val future = control.enableTorch(on)
        future.addListener({
            val ok = try {
                future.get()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Torch failed", e)
                false
            }
            onResult(ok)
        }, mainExecutor)
    }

    /** Flips the torch (OpenScan's _toggleTorch). */
    @MainThread
    fun toggleTorch(onResult: (Boolean) -> Unit = {}) = setTorch(!state.torchOn, onResult)

    /**
     * Follows a zoom slider (OpenScan's _onZoomSliderChanged): the state's ratio moves at once, the camera follows at most every 60 ms with the
     * latest target, and [ScanCameraState.zooming] is set until [finishZoom].
     */
    @MainThread
    fun setZoom(ratio: Float) {
        val s = state
        if (camera == null || !s.zoomSupported) return
        val target = ratio.coerceIn(s.minZoom, s.maxZoom)
        update { copy(zoomRatio = target, zooming = true) }
        zoomThrottle.request(target)
    }

    /** The slider was released (OpenScan's _onZoomSliderChangeEnd): any held-back target is applied now. */
    @MainThread
    fun finishZoom() {
        zoomThrottle.finish()
        update { copy(zooming = false) }
    }

    /**
     * Focuses and meters on a point of the preview (OpenScan's _onTapToFocus, camera side): [x] and [y] are 0..1 across the preview as shown on
     * [display], mapped to the sensor the way OpenScan's camera plugin did (DisplayOrientedMeteringPointFactory, which also mirrors the front
     * camera). It assumes the preview is shown uncropped. A camera that rejects focus metering turns [ScanCameraState.focusSupported] off.
     */
    @MainThread
    fun focusAt(x: Float, y: Float, display: Display) {
        val info = camera?.cameraInfo ?: return
        focusAt(DisplayOrientedMeteringPointFactory(display, info, 1f, 1f).createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)))
    }

    /** Focuses and meters on [point], e.g. one from a PreviewView's meteringPointFactory, which also accounts for its crop. */
    @MainThread
    fun focusAt(point: MeteringPoint) {
        val c = camera ?: return
        if (!state.focusSupported) return
        val future = try {
            c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, FLAGS_AF_AE).build())
        } catch (e: Exception) {
            Log.w(TAG, "Tap-to-focus unsupported, disabling", e)
            update { copy(focusSupported = false) }
            return
        }
        future.addListener({
            try {
                future.get()
            } catch (e: ExecutionException) {
                // A newer tap or zoom cancels the running action; only a real rejection means focus metering does not work here.
                if (e.cause !is CameraControl.OperationCanceledException && c === camera) {
                    Log.w(TAG, "Tap-to-focus failed, disabling", e)
                    update { copy(focusSupported = false) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tap-to-focus failed", e)
            }
        }, mainExecutor)
    }

    private fun bindUseCases(p: ProcessCameraProvider) {
        val owner = owner ?: return
        val hasBack = hasCamera(p, CameraSelector.DEFAULT_BACK_CAMERA)
        val hasFront = hasCamera(p, CameraSelector.DEFAULT_FRONT_CAMERA)
        val lens = when {
            requestedLens == CameraLens.BACK && hasBack || requestedLens == CameraLens.FRONT && !hasFront && hasBack -> CameraLens.BACK
            hasFront -> CameraLens.FRONT
            else -> {
                fail(CameraError.NO_CAMERA, null)
                return
            }
        }
        val selector = if (lens == CameraLens.BACK) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        try {
            val info = selector.filter(p.availableCameraInfos).first()
            val shape = sensorShape(info)
            val preview = Preview.Builder()
                .setResolutionSelector(streamSelector(shape, CameraSizes.PREVIEW_BOUND))
                .setTargetRotation(targetRotation)
                .build()
                .also { it.setSurfaceProvider(surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setJpegQuality(STILL_JPEG_QUALITY)
                .setResolutionSelector(stillSelector(shape))
                .setTargetRotation(targetRotation)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(streamSelector(shape, CameraSizes.ANALYSIS_BOUND))
                .setTargetRotation(targetRotation)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            this.preview = preview
            this.imageCapture = capture
            this.imageAnalysis = analysis
            val camera = p.bindToLifecycle(owner, selector, preview, capture, analysis)
            attachCamera(camera, lens, hasBack && hasFront)
        } catch (e: Exception) {
            fail(CameraError.BIND_FAILED, e)
        }
    }

    private fun hasCamera(p: ProcessCameraProvider, selector: CameraSelector): Boolean = try {
        p.hasCamera(selector)
    } catch (e: Exception) {
        false
    }

    private fun attachCamera(camera: Camera, lens: CameraLens, canSwitch: Boolean) {
        this.camera = camera
        val info = camera.cameraInfo
        val zoom = info.zoomState.value
        update {
            ScanCameraState(
                status = CameraStatus.OPENING,
                lens = lens,
                canSwitchLens = canSwitch,
                torchAvailable = info.hasFlashUnit(),
                minZoom = zoom?.minZoomRatio ?: 1f,
                maxZoom = zoom?.maxZoomRatio ?: 1f,
                zoomRatio = zoom?.zoomRatio ?: 1f,
                focusSupported = focusMeteringSupported(info),
                sensorRotationDegrees = info.sensorRotationDegrees,
                stillSize = imageCapture?.resolutionInfo?.resolution?.toDim(),
                analysisSize = imageAnalysis?.resolutionInfo?.resolution?.toDim(),
                cameraId = cameraId(info),
            )
        }
        info.cameraState.observeForever(cameraStateObserver)
        info.torchState.observeForever(torchObserver)
        info.zoomState.observeForever(zoomObserver)
    }

    private fun detachCamera() {
        val info = camera?.cameraInfo ?: return
        info.cameraState.removeObserver(cameraStateObserver)
        info.torchState.removeObserver(torchObserver)
        info.zoomState.removeObserver(zoomObserver)
        camera = null
    }

    private fun onCameraState(cs: CameraState) {
        val error = cs.error?.let { CameraError.fromCameraX(it.code) }
        val status = when (cs.type) {
            CameraState.Type.PENDING_OPEN, CameraState.Type.OPENING -> CameraStatus.OPENING
            CameraState.Type.OPEN -> CameraStatus.READY
            CameraState.Type.CLOSING, CameraState.Type.CLOSED -> CameraStatus.CLOSED
        }
        if (status == CameraStatus.OPENING) startOpenTimeout() else cancelOpenTimeout()
        update {
            // A watchdog timeout stands until the camera opens or CameraX reports something more specific.
            val keptTimeout = this.error == CameraError.OPEN_TIMEOUT && status == CameraStatus.OPENING && error == null
            copy(status = status, error = if (keptTimeout) CameraError.OPEN_TIMEOUT else error)
        }
    }

    private fun onZoomState(z: ZoomState) {
        // While a gesture is under way the requested ratio leads; the camera's own value takes over once it ends (or is reset on reopening).
        update {
            val follow = !zoomThrottle.isZooming && zoomThrottle.pending == null
            copy(minZoom = z.minZoomRatio, maxZoom = z.maxZoomRatio, zoomRatio = if (follow) z.zoomRatio else zoomRatio)
        }
    }

    private fun startOpenTimeout() {
        if (openTimeout != null) return
        val token = generation
        val r = Runnable {
            openTimeout = null
            if (token == generation && state.status == CameraStatus.OPENING && state.error == null) update { copy(error = CameraError.OPEN_TIMEOUT) }
        }
        openTimeout = r
        mainHandler.postDelayed(r, OPEN_TIMEOUT_MS)
    }

    private fun cancelOpenTimeout() {
        openTimeout?.let { mainHandler.removeCallbacks(it) }
        openTimeout = null
    }

    private fun analyze(image: androidx.camera.core.ImageProxy) {
        try {
            val listener = frameListener ?: return
            val y = image.planes[0]
            listener(y.buffer.apply { rewind() }, y.rowStride, image.width, image.height)
        } catch (e: Exception) {
            Log.w(TAG, "Frame listener failed", e)
        } finally {
            image.close()
        }
    }

    private fun fail(error: CameraError, e: Exception?) {
        Log.w(TAG, "Camera unavailable: $error", e)
        cancelOpenTimeout()
        update { ScanCameraState(error = error) }
    }

    private inline fun update(change: ScanCameraState.() -> ScanCameraState) {
        val next = state.change()
        if (next == state) return
        state = next
        stateListener?.invoke(next)
    }

    private fun stillSelector(shape: Dim?): ResolutionSelector = ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        .setResolutionFilter(ResolutionFilter { sizes, _ -> CameraSizes.stillPreference(sizes.map { it.toDim() }, shape).map { it.toSize() } })
        .build()

    private fun streamSelector(shape: Dim?, bound: Dim): ResolutionSelector {
        val builder = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(bound.width, bound.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        if (shape != null) {
            builder.setResolutionFilter(ResolutionFilter { sizes, _ ->
                CameraSizes.streamPreference(sizes.map { it.toDim() }, shape, bound).map { it.toSize() }
            })
        }
        return builder.build()
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun sensorShape(info: CameraInfo): Dim? = try {
        Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?.takeIf { it.width() > 0 && it.height() > 0 }
            ?.let { Dim(it.width(), it.height()) }
    } catch (e: Exception) {
        null
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun cameraId(info: CameraInfo): String? = try {
        Camera2CameraInfo.from(info).cameraId
    } catch (e: Exception) {
        null
    }

    private fun focusMeteringSupported(info: CameraInfo): Boolean = try {
        val centre = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        info.isFocusMeteringSupported(FocusMeteringAction.Builder(centre, FLAGS_AF_AE).build())
    } catch (e: Exception) {
        false
    }

    private fun ignoreSuperseded(future: com.google.common.util.concurrent.ListenableFuture<Void>, what: String) {
        // A superseded call fails with OperationCanceledException, which is expected (OpenScan swallowed these too).
        future.addListener({
            try {
                future.get()
            } catch (e: Exception) {
                if (e.cause !is CameraControl.OperationCanceledException) Log.w(TAG, "$what failed", e)
            }
        }, mainExecutor)
    }

    private fun Size.toDim() = Dim(width, height)

    private fun Dim.toSize() = Size(width, height)
}
