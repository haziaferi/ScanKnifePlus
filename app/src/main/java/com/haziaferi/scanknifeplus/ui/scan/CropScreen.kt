package com.haziaferi.scanknifeplus.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.haziaferi.scanknifeplus.scanner.cv.Pt
import com.haziaferi.scanknifeplus.scanner.cv.Quad
import com.paperknifeplus.app.R
import com.paperknifeplus.app.ui.theme.LocalScanPalette
import java.io.File
import kotlin.math.roundToInt

/**
 * Crop: the page's kept original ([source]) with four corners to drag onto the page's edges, starting from the whole photo. Apply hands the
 * corners to [onApply] in fractions of the original's upright size, the form ScanLibrary.recropPage takes.
 */
@Composable
internal fun CropScreen(source: File, onApply: (Quad) -> Unit, onCancel: () -> Unit) {
    val c = LocalScanPalette.current
    var imageSize by remember { mutableStateOf<Size?>(null) }
    var corners by remember { mutableStateOf(FULL) }
    Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.scan_crop_title), style = scanText(18, FontWeight.ExtraBold), color = c.tx,
                modifier = Modifier.semantics { heading() }
            )
            Text(stringResource(R.string.scan_crop_hint), style = scanText(13, FontWeight.Medium), color = c.mu)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
            val density = LocalDensity.current
            val box = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
            val rect = imageSize?.let { fitted(it, box) }
            AsyncImage(
                model = source, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                onSuccess = { imageSize = it.painter.intrinsicSize }
            )
            if (rect != null) {
                val current by rememberUpdatedState(rect)
                Canvas(Modifier.fillMaxSize()) {
                    val p = corners.map { Offset(rect.left + it.x * rect.width, rect.top + it.y * rect.height) }
                    val path = Path().apply {
                        moveTo(p[0].x, p[0].y)
                        p.drop(1).forEach { lineTo(it.x, it.y) }
                        close()
                    }
                    drawPath(path, c.bg.copy(alpha = 0.7f), style = Stroke(5.dp.toPx()))
                    drawPath(path, c.ring, style = Stroke(3.dp.toPx()))
                }
                val names = listOf(
                    R.string.scan_corner_top_left, R.string.scan_corner_top_right, R.string.scan_corner_bottom_right, R.string.scan_corner_bottom_left
                )
                corners.forEachIndexed { i, f ->
                    val name = stringResource(names[i])
                    val half = with(density) { 24.dp.toPx() }
                    Box(
                        Modifier
                            .offset {
                                IntOffset((rect.left + f.x * rect.width - half).roundToInt(), (rect.top + f.y * rect.height - half).roundToInt())
                            }
                            .size(48.dp)
                            .semantics { contentDescription = name }
                            .pointerInput(i) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    val r = current
                                    corners = corners.toMutableList().also {
                                        val moved = it[i] + Offset(drag.x / r.width, drag.y / r.height)
                                        it[i] = Offset(moved.x.coerceIn(0f, 1f), moved.y.coerceIn(0f, 1f))
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(c.ring).border(3.dp, c.bg, CircleShape))
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlowButton(stringResource(R.string.scan_cancel), filled = false, enabled = true, onClick = onCancel)
            Spacer(Modifier.weight(1f))
            FlowButton(stringResource(R.string.scan_apply), filled = true, enabled = imageSize != null) {
                val q = corners.map { Pt(it.x.toDouble(), it.y.toDouble()) }
                onApply(Quad(q[0], q[1], q[2], q[3]))
            }
        }
    }
}

/** A 48 dp pill button of the flow's screens: filled with the accent for the screen's main action, outlined otherwise. */
@Composable
internal fun FlowButton(label: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalScanPalette.current
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .then(
                if (filled) {
                    Modifier.background(c.acf.copy(alpha = if (enabled) 1f else 0.5f))
                } else {
                    Modifier.border(1.dp, c.bd, RoundedCornerShape(24.dp))
                }
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = scanText(15, FontWeight.Bold), color = if (filled) c.onAcf else c.tx)
    }
}

/** Where an image of [image] size shows inside [box] with ContentScale.Fit, centred. */
private fun fitted(image: Size, box: Size): Rect? {
    if (image.width <= 0f || image.height <= 0f || box.width <= 0f || box.height <= 0f) return null
    val scale = minOf(box.width / image.width, box.height / image.height)
    val w = image.width * scale
    val h = image.height * scale
    return Rect(Offset((box.width - w) / 2, (box.height - h) / 2), Size(w, h))
}

private val FULL = listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))
