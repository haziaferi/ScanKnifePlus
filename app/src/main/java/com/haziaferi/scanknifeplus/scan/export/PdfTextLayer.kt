package com.haziaferi.scanknifeplus.scan.export

import android.util.Log
import com.haziaferi.scanknifeplus.ocr.OcrPage
import com.haziaferi.scanknifeplus.ocr.OcrWord
import com.haziaferi.scanknifeplus.scanner.export.PdfImageRect
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import kotlin.math.PI

/** A word's box on the PDF page, in points from the lower left corner. */
internal data class PdfWordBox(val left: Double, val bottom: Double, val right: Double, val top: Double)

/**
 * The searchable text layer: each recognised word drawn as invisible text over its place in the page image, so it can be found, selected and
 * copied in any PDF reader. The font is Liberation Sans from pdfbox-android's assets, embedded as a subset; Helvetica's WinAnsi encoding cannot
 * carry "€" or most non-Latin text.
 */
internal object PdfTextLayer {
    /** Where pdfbox-android ships the font, in its AAR's assets (assets are not code, so R8 leaves them alone). */
    const val FONT = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf"

    /** Loads the font into [doc], subset-embedded: only the glyphs the text uses are written. Needs PDFBoxResourceLoader.init. Blocking. */
    fun loadFont(doc: PDDocument): PDFont = PDFBoxResourceLoader.getStream(FONT).use { PDType0Font.load(doc, it, true) }

    /**
     * Where [word] goes on a page whose image is drawn at [image]. Word boxes are fractions of the image with a top-left origin; the PDF's
     * origin is bottom-left, so the vertical axis flips.
     */
    fun boxOf(word: OcrWord, image: PdfImageRect): PdfWordBox = PdfWordBox(
        left = image.x + word.left * image.width,
        bottom = image.y + (1 - word.bottom) * image.height,
        right = image.x + word.right * image.width,
        top = image.y + (1 - word.top) * image.height,
    )

    /**
     * Draws [ocr]'s words on [stream] over the image at [image]. A word the font cannot encode is skipped and logged, never failing the page. On a
     * page orientation detection turned before reading, the words run as the text does in the image: turned back anticlockwise.
     */
    fun draw(stream: PDPageContentStream, font: PDFont, ocr: OcrPage, image: PdfImageRect) {
        val turn = ocr.turnedClockwise.mod(FULL_TURN)
        val ascent = font.fontDescriptor?.ascent?.takeIf { it > 0 } ?: DEFAULT_ASCENT
        val descent = font.fontDescriptor?.descent?.takeIf { it < 0 } ?: DEFAULT_DESCENT
        val em = (ascent - descent) / 1000f
        stream.beginText()
        stream.setRenderingMode(RenderingMode.NEITHER)
        for (word in ocr.words) {
            val text = word.text.trim()
            if (text.isEmpty()) continue
            val width = try {
                font.encode(text)
                font.getStringWidth(text) / 1000f
            } catch (e: Exception) {
                Log.i(TAG, "Left out a word the font cannot show (${text.length} characters)", e)
                continue
            }
            if (width <= 0f) continue
            val box = boxOf(word, image)
            val sideways = turn == 90 || turn == 270
            // Across the text the box holds one line from ascent to descent; along it the word is stretched to the box's length.
            val across = (if (sideways) box.right - box.left else box.top - box.bottom).toFloat()
            val along = (if (sideways) box.top - box.bottom else box.right - box.left).toFloat()
            if (across <= 0f || along <= 0f) continue
            val size = across / em
            val drop = -descent / 1000f * size
            val (x, y) = when (turn) {
                90 -> box.right - drop to box.bottom
                180 -> box.right to box.top - drop
                270 -> box.left + drop to box.top
                else -> box.left to box.bottom + drop
            }
            stream.setFont(font, size)
            stream.setHorizontalScaling(along / (width * size) * 100f)
            stream.setTextMatrix(Matrix.getRotateInstance(turn * PI / 180, x.toFloat(), y.toFloat()))
            stream.showText(text)
        }
        stream.endText()
    }

    private const val FULL_TURN = 360
    private const val DEFAULT_ASCENT = 905f
    private const val DEFAULT_DESCENT = -212f
    private const val TAG = "PdfTextLayer"
}
