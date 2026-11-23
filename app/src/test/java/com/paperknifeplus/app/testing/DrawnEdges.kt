package com.paperknifeplus.app.testing

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import kotlin.math.abs
import org.junit.Assert.assertTrue

/**
 * The top edge of what this node draws across the columns [xs] (pixels from its left): in each, the first row from the top that leaves the
 * colour of the column's top pixel, such as a text field's outline under the room its label keeps above it. Fails unless every column finds
 * an edge and the columns agree within a pixel, so a stray mark or an empty column cannot pass for the edge.
 */
fun SemanticsNodeInteraction.drawnTop(xs: List<Int>): Int {
    val pixels = captureToImage().toPixelMap()
    val rows = xs.map { x ->
        val ground = pixels[x, 0]
        (0 until pixels.height).firstOrNull { y ->
            val c = pixels[x, y]
            abs(c.red - ground.red) + abs(c.green - ground.green) + abs(c.blue - ground.blue) + abs(c.alpha - ground.alpha) > 0.1f
        } ?: -1
    }
    assertTrue("an edge is drawn in every column: $rows", rows.all { it >= 0 })
    assertTrue("the columns find one edge: $rows", rows.max() - rows.min() <= 1)
    return rows.min()
}
