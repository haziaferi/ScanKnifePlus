package com.paperknifeplus.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/** Limits a pan [offset] of content of [size] zoomed by [scale] around its centre so no empty space shows; no panning at scale 1. */
fun zoomPanClamp(offset: Offset, size: Size, scale: Float): Offset {
    if (scale <= 1f) return Offset.Zero
    val maxX = size.width * (scale - 1) / 2
    val maxY = size.height * (scale - 1) / 2
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** The pan offset that keeps the point at [tap] under the finger when content of [size] zooms to [scale] around its centre. */
fun doubleTapZoomOffset(tap: Offset, size: Size, scale: Float): Offset =
    Offset((size.width / 2 - tap.x) * (scale - 1), (size.height / 2 - tap.y) * (scale - 1))
