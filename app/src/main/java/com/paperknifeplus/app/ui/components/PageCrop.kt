package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The part of a page the Crop tool keeps, as the page is displayed (its /Rotate applied) and measured from the top-left corner of its
 * current crop box, y down, as the Sign and Watermark overlays measure.
 */
sealed interface CropArea {
    /**
     * Edges at fractions of the displayed page's width ([left], [right]) and height ([top], [bottom]); 0 <= left < right <= 1, same for y.
     * One frame scales to pages of every size (question 14a).
     */
    data class Fractions(val left: Float, val top: Float, val right: Float, val bottom: Float) : CropArea {
        init {
            require(left in 0f..1f && right in 0f..1f && top in 0f..1f && bottom in 0f..1f) { "Crop fractions must be within 0..1" }
            require(left < right && top < bottom) { "The crop area is empty" }
        }
    }
}

/**
 * The new crop box of a page whose current one is [box]: [area] turned from displayed coordinates into user space, so a rotated page is
 * cut where the user sees it, and offset by the crop box's lower-left corner.
 */
internal fun cropRectangle(box: PageBox, area: CropArea.Fractions): PDRectangle {
    // displayToPdf measures y up from the displayed bottom edge.
    val a = box.displayToPdf(area.left * box.displayWidth, box.displayHeight - area.top * box.displayHeight)
    val b = box.displayToPdf(area.right * box.displayWidth, box.displayHeight - area.bottom * box.displayHeight)
    val x = minOf(a.x, b.x)
    val y = minOf(a.y, b.y)
    return PDRectangle(x, y, maxOf(a.x, b.x) - x, maxOf(a.y, b.y) - y)
}

/**
 * Sets the crop box of each page of [document] at [pages] (0-based; null for every page, indices past the end ignored) to [area] of its
 * current crop box, leaving the media box and the content as they are: hidden content stays in the file. Returns how many pages were
 * cropped.
 */
fun cropPages(document: PDDocument, area: CropArea.Fractions, pages: Collection<Int>? = null): Int {
    val all = document.pages.toList()
    val targets: List<PDPage> = if (pages == null) all else pages.toSortedSet().mapNotNull(all::getOrNull)
    for (page in targets) page.cropBox = cropRectangle(PageBox.of(page), area)
    return targets.size
}

/**
 * Writes [input], opened with [password], to [output] with [area] cropped on [pages] as [cropPages] does, as protected as the source
 * ([sameProtection]). Returns how many pages were cropped; throws on failure.
 */
internal suspend fun cropPdf(context: Context, input: Uri, output: Uri, password: String?, area: CropArea.Fractions, pages: Collection<Int>?): Int =
    withContext(Dispatchers.IO) {
        context.contentResolver.requireInputStream(input).use { stream ->
            PDDocument.load(stream, password.orEmpty()).use { document ->
                val protection = document.sameProtection(password.orEmpty())
                val cropped = cropPages(document, area, pages)
                protection?.let(document::protect)
                saveAndFlush(context, document, output)
                cropped
            }
        }
    }
