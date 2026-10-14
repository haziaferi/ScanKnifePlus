package com.paperknifeplus.app.ui.components

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle

/**
 * The part of a page the Crop tool keeps, as the page is displayed (its /Rotate applied) and measured from the top-left corner of its
 * current crop box, y down, as the Sign and Watermark overlays measure.
 */
sealed interface CropArea {
    /** Edges at fractions of the displayed page's width ([left], [right]) and height ([top], [bottom]); 0 <= left < right <= 1, same for y. */
    data class Fractions(val left: Float, val top: Float, val right: Float, val bottom: Float) : CropArea {
        init {
            require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f) { "Crop fractions must be within 0..1" }
            require(left < right && top < bottom) { "The crop area is empty" }
        }
    }

    /** Edges in points; the area is cut to the page, so one rectangle can serve pages of different sizes. */
    data class Points(val left: Float, val top: Float, val right: Float, val bottom: Float) : CropArea {
        init {
            require(left < right && top < bottom) { "The crop area is empty" }
        }
    }
}

/**
 * The new crop box of a page whose current one is [box]: [area] turned from displayed coordinates into user space, so a rotated page is
 * cut where the user sees it, and offset by the crop box's lower-left corner. Null when a [CropArea.Points] area misses the page.
 */
internal fun cropRectangle(box: PageBox, area: CropArea): PDRectangle? {
    val (left, top, right, bottom) = when (area) {
        is CropArea.Fractions -> listOf(
            area.left * box.displayWidth,
            area.top * box.displayHeight,
            area.right * box.displayWidth,
            area.bottom * box.displayHeight
        )
        is CropArea.Points -> listOf(
            area.left.coerceAtLeast(0f),
            area.top.coerceAtLeast(0f),
            area.right.coerceAtMost(box.displayWidth),
            area.bottom.coerceAtMost(box.displayHeight)
        )
    }
    if (left >= right || top >= bottom) return null
    // displayToPdf measures y up from the displayed bottom edge.
    val a = box.displayToPdf(left, box.displayHeight - top)
    val b = box.displayToPdf(right, box.displayHeight - bottom)
    val x = minOf(a.x, b.x)
    val y = minOf(a.y, b.y)
    return PDRectangle(x, y, maxOf(a.x, b.x) - x, maxOf(a.y, b.y) - y)
}

/**
 * Sets the crop box of each page of [document] at [pages] (0-based; null for every page, indices past the end ignored) to [area] of its
 * current crop box, leaving the media box and the content as they are: hidden content stays in the file. Returns how many pages were
 * cropped; a page that [CropArea.Points] misses is left alone.
 */
fun cropPages(document: PDDocument, area: CropArea, pages: Collection<Int>? = null): Int {
    val all = document.pages.toList()
    val targets: List<PDPage> = if (pages == null) all else pages.toSortedSet().mapNotNull(all::getOrNull)
    var cropped = 0
    for (page in targets) {
        val rectangle = cropRectangle(PageBox.of(page), area) ?: continue
        page.cropBox = rectangle
        cropped++
    }
    return cropped
}
