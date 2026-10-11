package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The part of a page viewers show: its cropBox in user space ([left], [bottom], [width], [height]) and its /Rotate, one of 0, 90, 180 or
 * 270 degrees clockwise. "Displayed" below means the page as shown, rotation applied, with x to the right.
 */
data class PageBox(val left: Float, val bottom: Float, val width: Float, val height: Float, val rotation: Int) {
    private val quarterTurned: Boolean get() = rotation == 90 || rotation == 270
    val displayWidth: Float get() = if (quarterTurned) height else width
    val displayHeight: Float get() = if (quarterTurned) width else height
    val displayAspect: Float get() = displayWidth / displayHeight

    companion object {
        /** [page]'s cropBox, which PDFBox already falls back to the mediaBox for and clips to it, and its rotation. */
        fun of(page: PDPage): PageBox = page.cropBox.let { PageBox(it.lowerLeftX, it.lowerLeftY, it.width, it.height, normalizeRotation(page.rotation)) }
    }
}

/** [degrees] as one of 0, 90, 180 or 270, rounding toward zero to a quarter turn as pdfium does. */
fun normalizeRotation(degrees: Int): Int = ((degrees / 90) % 4 + 4) % 4 * 90

data class PdfPoint(val x: Float, val y: Float)

/** The user-space point [px], [py] points from the displayed page's lower-left corner (y up). */
fun PageBox.displayToPdf(px: Float, py: Float): PdfPoint = when (rotation) {
    90 -> PdfPoint(left + width - py, bottom + px)
    180 -> PdfPoint(left + width - px, bottom + height - py)
    270 -> PdfPoint(left + py, bottom + height - px)
    else -> PdfPoint(left + px, bottom + py)
}

/** Where user-space [x], [y] shows on the displayed page, in points from its top-left corner (y down); the inverse of [displayToPdf]. */
fun PageBox.pdfToDisplay(x: Float, y: Float): PdfPoint {
    val u = x - left
    val v = y - bottom
    return when (rotation) {
        90 -> PdfPoint(v, u)
        180 -> PdfPoint(width - u, v)
        270 -> PdfPoint(height - v, width - u)
        else -> PdfPoint(u, height - v)
    }
}

/** The user-space point at fractions [nx] across and [ny] down the displayed page, as the Sign and Watermark overlays record them. */
fun PageBox.toPdfPoint(nx: Float, ny: Float): PdfPoint = displayToPdf(nx * displayWidth, displayHeight - ny * displayHeight)

/** Start of a text line in user space and the quarter turn ([rotation], counter-clockwise) that shows it upright on the displayed page. */
data class TextPlacement(val x: Float, val y: Float, val rotation: Int) {
    /** The text matrix, built from exact quarter turns rather than trigonometry. */
    fun matrix(): Matrix = when (rotation) {
        90 -> Matrix(0f, 1f, -1f, 0f, x, y)
        180 -> Matrix(-1f, 0f, 0f, -1f, x, y)
        270 -> Matrix(0f, -1f, 1f, 0f, x, y)
        else -> Matrix(1f, 0f, 0f, 1f, x, y)
    }
}

/**
 * Where the Page Numbers tool puts text [textWidth] wide in a [fontSize] font: [margin] points in from the [position] corner ("Top Left" to
 * "Bottom Right") of the displayed page, so a rotated page gets its number in the corner the user picked, reading upright.
 */
fun pageNumberPlacement(position: String, textWidth: Float, fontSize: Float, box: PageBox, margin: Float = 30f): TextPlacement {
    val px = when {
        position.contains("Left") -> margin
        position.contains("Center") -> (box.displayWidth - textWidth) / 2
        else -> box.displayWidth - textWidth - margin
    }
    val py = if (position.contains("Top")) box.displayHeight - margin - fontSize else margin
    val origin = box.displayToPdf(px, py)
    return TextPlacement(origin.x, origin.y, box.rotation)
}

/**
 * Draws [image] centred at fractions [nx], [ny] of the displayed page [box], [nw] of its width wide, turned [angle] degrees clockwise on
 * screen and at [opacity]. The page's own rotation is added so the stamp looks on the page as it did in the overlay.
 */
internal fun PDPageContentStream.drawStamp(image: PDImageXObject, box: PageBox, nx: Float, ny: Float, nw: Float, angle: Float, opacity: Float = 1f) {
    val width = box.displayWidth * nw
    val height = width * (image.height.toFloat() / image.width.toFloat())
    val centre = box.toPdfPoint(nx, ny)
    saveGraphicsState()
    val matrix = Matrix()
    matrix.translate(centre.x, centre.y)
    matrix.rotate(Math.toRadians((box.rotation - angle).toDouble()))
    matrix.translate(-centre.x, -centre.y)
    transform(matrix)
    if (opacity < 1f) setGraphicsStateParameters(PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = opacity })
    drawImage(image, centre.x - width / 2, centre.y - height / 2, width, height)
    restoreGraphicsState()
}

/** Opens a content stream appended to [page]'s own, in a fresh graphics state, as every stamping tool draws. */
internal fun PDDocument.appendTo(page: PDPage): PDPageContentStream = PDPageContentStream(this, page, PDPageContentStream.AppendMode.APPEND, true, true)

/**
 * Loads [source] with [password] (empty for none), removes its encryption, applies [edit] and saves the result to [output], as Sign,
 * Watermark, Page Numbers and Metadata do. Blocking; throws on failure.
 */
internal fun rewritePdf(context: Context, source: Uri, password: String, output: Uri, edit: (PDDocument) -> Unit) {
    context.contentResolver.requireInputStream(source).use { input ->
        PDDocument.load(input, password).use { document ->
            if (document.isEncrypted) document.isAllSecurityToBeRemoved = true
            edit(document)
            saveAndFlush(context, document, output)
        }
    }
}

/** The [PageBox] of every page of [uri], or empty when it cannot be read with [password] (wrong passwords included). */
internal suspend fun readPageBoxes(context: Context, uri: Uri, password: String?): List<PageBox> = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.requireInputStream(uri).use { input ->
            PDDocument.load(input, password.orEmpty(), MemoryUsageSetting.setupTempFileOnly()).use { doc -> doc.pages.map(PageBox::of) }
        }
    } catch (e: Exception) {
        // PDFBox throws unchecked exceptions on some malformed files, as inspectPdf notes.
        Log.w("PdfPlacement", "Cannot read the pages of $uri", e)
        emptyList()
    }
}
