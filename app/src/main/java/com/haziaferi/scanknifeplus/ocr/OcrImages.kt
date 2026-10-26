package com.haziaferi.scanknifeplus.ocr

import com.haziaferi.scanknifeplus.scanner.cv.PageSize
import com.haziaferi.scanknifeplus.scanner.cv.RgbaImage
import com.haziaferi.scanknifeplus.scanner.filter.DocumentFilterUtils
import com.haziaferi.scanknifeplus.scanner.filter.DocumentFilters
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An 8-bit grey image, row-major without padding, as Tesseract takes it. */
internal class GreyImage(val width: Int, val height: Int, val pixels: ByteArray)

/** A word as Tesseract's result iterator gives it: box in pixels of the image read, and whether it starts a block, paragraph or line. */
internal class RawWord(
    val text: String,
    val confidence: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val startsBlock: Boolean,
    val startsParagraph: Boolean,
    val startsLine: Boolean,
)

/** The pure image and geometry work around Tesseract: the grey copy, its size at the reading DPI, quarter turns and mapping words back. */
internal object OcrImages {
    private const val MM_PER_INCH = 25.4

    /** The contrast-stretched grey copy OCR reads (questions.md, question 8): the scanner's Grayscale filter, whatever filter the page has. */
    fun grey(image: RgbaImage): GreyImage {
        DocumentFilters.Grayscale.apply(image.pixels, image.width, image.height)
        val rgba = image.pixels
        return GreyImage(image.width, image.height, ByteArray(image.width * image.height) { rgba[it * 4] })
    }

    /**
     * The size of a [width] x [height] image read at [dpi] as a page whose long edge is [longEdgeMm]: long edge to long edge, the short edge
     * by the image's aspect, so the image is scaled without distortion.
     */
    fun targetSize(width: Int, height: Int, longEdgeMm: Double, dpi: Int): PageSize {
        val longPx = max(1, (longEdgeMm / MM_PER_INCH * dpi).roundToInt())
        val shortPx = max(1, (longPx.toDouble() * min(width, height) / max(width, height)).roundToInt())
        return if (width >= height) PageSize(longPx, shortPx) else PageSize(shortPx, longPx)
    }

    /** [src] scaled to [width] x [height]: box-averaged when shrinking, bilinear (pixel centres aligned) when enlarging. */
    fun resized(src: GreyImage, width: Int, height: Int): GreyImage = when {
        width == src.width && height == src.height -> src
        width <= src.width && height <= src.height ->
            GreyImage(width, height, DocumentFilterUtils.downscaleGray(src.pixels, src.width, src.height, width, height))
        else -> bilinear(src, width, height)
    }

    private fun bilinear(src: GreyImage, width: Int, height: Int): GreyImage {
        val out = ByteArray(width * height)
        val sx = src.width.toDouble() / width
        val sy = src.height.toDouble() / height
        val p = src.pixels
        fun at(x: Int, y: Int): Int = p[y * src.width + x].toInt() and 0xff
        for (y in 0 until height) {
            val fy = ((y + 0.5) * sy - 0.5).coerceIn(0.0, src.height - 1.0)
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, src.height - 1)
            val ty = fy - y0
            for (x in 0 until width) {
                val fx = ((x + 0.5) * sx - 0.5).coerceIn(0.0, src.width - 1.0)
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, src.width - 1)
                val tx = fx - x0
                val top = at(x0, y0) * (1 - tx) + at(x1, y0) * tx
                val bottom = at(x0, y1) * (1 - tx) + at(x1, y1) * tx
                out[y * width + x] = (top * (1 - ty) + bottom * ty).roundToInt().toByte()
            }
        }
        return GreyImage(width, height, out)
    }

    /** [src] turned clockwise by [degrees]: 0, 90, 180 or 270. */
    fun rotatedClockwise(src: GreyImage, degrees: Int): GreyImage {
        if (degrees == 0) return src
        val w = src.width
        val h = src.height
        val p = src.pixels
        val out = ByteArray(p.size)
        for (y in 0 until h) for (x in 0 until w) {
            val v = p[y * w + x]
            when (degrees) {
                90 -> out[x * h + (h - 1 - y)] = v
                180 -> out[(h - 1 - y) * w + (w - 1 - x)] = v
                270 -> out[(w - 1 - x) * h + y] = v
                else -> throw IllegalArgumentException("Turn $degrees")
            }
        }
        return if (degrees == 180) GreyImage(w, h, out) else GreyImage(h, w, out)
    }

    /**
     * [raw] as page words: empty words dropped, block, paragraph and line counted up from 0 in reading order, and each box turned from pixels
     * of the [width] x [height] image that was read into fractions of the page before it was turned clockwise by [turnedClockwise].
     */
    fun words(raw: List<RawWord>, width: Int, height: Int, turnedClockwise: Int): List<OcrWord> {
        var block = -1
        var paragraph = -1
        var line = -1
        val words = ArrayList<OcrWord>(raw.size)
        for (word in raw) {
            // Counted before an empty word is skipped, so a block that starts with one still begins a new block.
            if (word.startsBlock) block++
            if (word.startsParagraph) paragraph++
            if (word.startsLine) line++
            val text = word.text.trim()
            if (text.isEmpty()) continue
            val u0 = word.left.toFloat() / width
            val v0 = word.top.toFloat() / height
            val u1 = word.right.toFloat() / width
            val v1 = word.bottom.toFloat() / height
            // The read image is the page turned clockwise; turning back is the inverse (counterclockwise) turn of the box.
            val box = when (turnedClockwise) {
                0 -> floatArrayOf(u0, v0, u1, v1)
                90 -> floatArrayOf(v0, 1 - u1, v1, 1 - u0)
                180 -> floatArrayOf(1 - u1, 1 - v1, 1 - u0, 1 - v0)
                270 -> floatArrayOf(1 - v1, u0, 1 - v0, u1)
                else -> throw IllegalArgumentException("Turn $turnedClockwise")
            }
            words += OcrWord(text, box[0], box[1], box[2], box[3], word.confidence, max(block, 0), max(paragraph, 0), max(line, 0))
        }
        return words
    }
}
