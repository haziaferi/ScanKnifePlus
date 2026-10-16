package com.haziaferi.scanknifeplus.ui.scan

import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.haziaferi.scanknifeplus.scan.camera.ScanCameraController
import com.haziaferi.scanknifeplus.scanner.cv.BookPage
import com.haziaferi.scanknifeplus.scanner.cv.BookSplit
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import kotlinx.coroutines.delay

/**
 * A preview surface for [ScanCameraController] without the camera-view library: a SurfaceView whose buffer is the camera's own resolution.
 * Camera output to a SurfaceView is turned upright by the system, so the view only needs the portrait aspect ratio. The surface is handed
 * over once it exists at that size, and invalidated when it is destroyed (the screen pauses), so CameraX asks again when it comes back.
 * Main thread only, which is where CameraX calls a surface provider by default.
 */
internal class SurfaceViewProvider : Preview.SurfaceProvider, SurfaceHolder.Callback {
    private var view: SurfaceView? = null
    private var request: SurfaceRequest? = null
    private var provided = false
    private var surfaceSize: Size? = null

    /** The resolution of the current request, in sensor orientation; null until the camera asks for a surface. */
    var resolution by mutableStateOf<Size?>(null)
        private set

    fun attach(surfaceView: SurfaceView) {
        view = surfaceView
        surfaceView.holder.addCallback(this)
        request?.let { surfaceView.holder.setFixedSize(it.resolution.width, it.resolution.height) }
    }

    fun detach() {
        view?.holder?.removeCallback(this)
        view = null
        surfaceSize = null
        drop()
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        drop()
        this.request = request
        resolution = request.resolution
        val context = view?.context
        if (context != null) {
            request.addRequestCancellationListener(ContextCompat.getMainExecutor(context)) {
                if (this.request === request) {
                    this.request = null
                    provided = false
                }
            }
        }
        view?.holder?.setFixedSize(request.resolution.width, request.resolution.height)
        provideIfReady()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceSize = Size(width, height)
        provideIfReady()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceSize = null
        drop()
    }

    private fun provideIfReady() {
        val r = request ?: return
        val v = view ?: return
        if (provided || surfaceSize != r.resolution || !v.holder.surface.isValid) return
        provided = true
        r.provideSurface(v.holder.surface, ContextCompat.getMainExecutor(v.context)) {}
    }

    /** Lets go of the current request: a surface already handed over is invalidated so CameraX requests a new one. */
    private fun drop() {
        val r = request ?: return
        request = null
        if (provided) r.invalidate() else r.willNotProvideSurface()
        provided = false
    }
}

/**
 * The camera preview, uncropped and centred in the space it is given, with the live outline drawn over it: a tap focuses there (25a) and a
 * pinch zooms. Binds [controller] to the screen's lifecycle while shown, so the camera closes when the screen pauses and when the
 * viewfinder leaves the screen. In [book] mode the outline carries the split line and the labels of the two pages it would add after
 * [count] pages.
 */
@Composable
internal fun CameraViewfinder(
    controller: ScanCameraController,
    quad: Quad?,
    sensorRotationDegrees: Int,
    book: Boolean,
    quarterTurns: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val provider = remember { SurfaceViewProvider() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        controller.bind(owner, provider)
        onDispose { controller.unbind() }
    }
    val res = provider.resolution
    val turned = sensorRotationDegrees % 180 != 0
    val aspect = when {
        res == null -> 3f / 4f
        turned -> res.height.toFloat() / res.width
        else -> res.width.toFloat() / res.height
    }
    val view = LocalView.current
    var focus by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(focus) {
        if (focus != null) {
            delay(FOCUS_RING_MS)
            focus = null
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier
                .aspectRatio(aspect)
                .pointerInput(controller) {
                    detectTapGestures { tap ->
                        val display = view.display ?: return@detectTapGestures
                        controller.focusAt(tap.x / size.width, tap.y / size.height, display)
                        focus = tap
                    }
                }
                .pointerInput(controller) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoomed = false
                        var ratio = controller.state.zoomRatio
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } >= 2) {
                                val change = event.calculateZoom()
                                if (change != 1f) {
                                    ratio *= change
                                    controller.setZoom(ratio)
                                    zoomed = true
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (zoomed) controller.finishZoom()
                    }
                }
        ) {
            AndroidView(
                factory = { SurfaceView(it).also(provider::attach) },
                onRelease = { provider.detach() },
                modifier = Modifier.fillMaxSize()
            )
            QuadOverlay(quad, if (book) quarterTurns else null)
            // Labels read upright only while the phone is; held sideways, the split line alone shows the pages.
            if (book && quad != null && quarterTurns == 0) BookLabels(quad, count, maxWidth.value, maxHeight.value)
            focus?.let { FocusRing(it) }
        }
    }
}

/**
 * The live outline (portrait, fractions of the preview), drawn dark under light so it reads over paper and over a dark desk. With
 * [bookTurns] (Book mode) it adds the centre split line of the book as it reads once the photo is turned clockwise by that many quarter turns.
 */
@Composable
private fun QuadOverlay(quad: Quad?, bookTurns: Int?) {
    val k = LocalScanPalette.current.camera
    if (quad == null) return
    Canvas(Modifier.fillMaxSize()) {
        fun Pt.at() = Offset((x * size.width).toFloat(), (y * size.height).toFloat())
        val path = Path().apply {
            val p = quad.points.map { it.at() }
            moveTo(p[0].x, p[0].y)
            p.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, k.outline.copy(alpha = 0.14f))
        drawPath(path, k.cam.copy(alpha = 0.6f), style = Stroke(5.dp.toPx(), join = StrokeJoin.Round))
        drawPath(path, k.outline, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
        if (bookTurns != null) {
            // The left page's edge along the split, as BookSplit cuts it; its quad is in image corner order, turned by bookTurns.
            val page = BookSplit.page(quad, BookPage.LEFT, BookSplit.DEFAULT_SPLIT, bookTurns).points
            val top = page[(1 - bookTurns).mod(4)].at()
            val bottom = page[(2 - bookTurns).mod(4)].at()
            val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
            drawLine(k.cam.copy(alpha = 0.6f), top, bottom, 4.5f.dp.toPx(), pathEffect = dash)
            drawLine(k.split, top, bottom, 2.5f.dp.toPx(), pathEffect = dash)
        }
    }
}

/** "Page n" under each half of the spread, for the two pages the next shot adds. */
@Composable
private fun BookLabels(quad: Quad, count: Int, widthDp: Float, heightDp: Float) {
    val k = LocalScanPalette.current.camera
    val left = quad.points.minOf { it.x }
    val right = quad.points.maxOf { it.x }
    val bottom = quad.points.maxOf { it.y }
    Row(
        Modifier
            .offset(x = (left * widthDp).dp, y = (bottom * heightDp).dp + 8.dp)
            .width(((right - left) * widthDp).dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        for (n in listOf(count + 1, count + 2)) {
            Text(
                stringResource(R.string.scan_page_label, n),
                style = scanText(13, FontWeight.Bold),
                color = k.onCam,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(k.cam.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun FocusRing(at: Offset) {
    val k = LocalScanPalette.current.camera
    val radius = with(LocalDensity.current) { 28.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(k.cam.copy(alpha = 0.6f), radius, at, style = Stroke(4.dp.toPx()))
        drawCircle(k.onCam, radius, at, style = Stroke(2.dp.toPx()))
    }
}

private const val FOCUS_RING_MS = 800L
