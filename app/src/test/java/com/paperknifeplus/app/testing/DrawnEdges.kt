package com.paperknifeplus.app.testing

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import kotlin.math.abs

/**
 * The first row, from the top of this node's image, where the column [x] (in pixels from its left) leaves the colour of its top pixel: the
 * top edge of what the node draws there, such as a text field's outline under the room its label keeps above it. -1 when nothing is drawn.
 */
fun SemanticsNodeInteraction.firstDrawnRow(x: Int): Int {
    val pixels = captureToImage().toPixelMap()
    val ground = pixels[x, 0]
    for (y in 0 until pixels.height) {
        val c = pixels[x, y]
        if (abs(c.red - ground.red) + abs(c.green - ground.green) + abs(c.blue - ground.blue) + abs(c.alpha - ground.alpha) > 0.1f) return y
    }
    return -1
}
