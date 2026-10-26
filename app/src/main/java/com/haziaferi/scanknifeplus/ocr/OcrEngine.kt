package com.haziaferi.scanknifeplus.ocr

import java.io.File

/** The language a job is read in; one per job (reading both at once was measured worse). */
enum class OcrLanguage(val code: String) {
    ENGLISH("eng"),
    ITALIAN("ita"),
}

/** The Reading choice: Tesseract's page segmentation mode (docs/ocr-bench/questions.md, question 3). */
enum class OcrReading(val pageSegMode: Int) {
    /** Fully automatic layout analysis: best on mixed layouts and multi-column books. */
    AUTOMATIC(3),

    /** One uniform block: keeps table rows in order and the table blocks automatic layout drops. */
    TABLES(6),

    /** One column of variable-size text: keeps receipt lines with their prices. */
    RECEIPTS(4),
}

/**
 * One recognised word. The box is in fractions (0 to 1) of the page image as it was passed to [OcrEngine.read], top-left origin, so it maps
 * onto the page however large the PDF draws it; a page orientation detection turned is mapped back. [block], [paragraph] and [line] count up in
 * reading order and tell where the text breaks.
 */
data class OcrWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
    val block: Int,
    val paragraph: Int,
    val line: Int,
)

/** A read page: its words in reading order, and the clockwise turn orientation detection applied before reading (0 when none). */
data class OcrPage(val words: List<OcrWord>, val turnedClockwise: Int = 0) {
    /** Tesseract's mean word confidence, 0 to 100; 0 for a page without words. */
    val meanConfidence: Int get() = if (words.isEmpty()) 0 else words.sumOf { it.confidence.toDouble() }.div(words.size).toInt()

    /** Whether the page reads like handwriting, whose text is unreliable (questions.md, question 7). */
    val looksHandwritten: Boolean get() = words.isNotEmpty() && meanConfidence < HANDWRITING_BELOW

    /**
     * The page as plain text without words below [minConfidence]: words on a line joined by spaces, lines by a line break, blocks by an empty
     * line. Paragraphs within a block are lines like any other, as Tesseract's own text output has them.
     */
    fun text(minConfidence: Int = 0): String {
        val sb = StringBuilder()
        var previous: OcrWord? = null
        for (word in words) {
            if (word.confidence < minConfidence || word.text.isBlank()) continue
            val last = previous
            if (last != null) {
                sb.append(
                    when {
                        word.block != last.block -> "\n\n"
                        word.paragraph != last.paragraph || word.line != last.line -> "\n"
                        else -> " "
                    },
                )
            }
            sb.append(word.text.trim())
            previous = word
        }
        return sb.toString()
    }

    companion object {
        /** Below this mean word confidence a page is flagged as looking handwritten. */
        const val HANDWRITING_BELOW = 55

        /** The .txt copy leaves out words below this confidence; the PDF's text layer keeps every word. */
        const val TEXT_FILE_MIN_CONFIDENCE = 40
    }
}

/** Reads page images; the Tesseract engine in the app, a fake in tests. Not thread-safe: one page at a time. */
interface OcrEngine : AutoCloseable {
    /**
     * Reads [page], an upright stored page JPEG, as a page whose long edge is [longEdgeMm] millimetres. It is read at 200 DPI of that size, as a
     * contrast-stretched grey copy, and turned first when orientation detection is confident. Null if it could not be read or [cancel] stopped
     * it. Blocking.
     */
    fun read(page: File, longEdgeMm: Double, language: OcrLanguage, reading: OcrReading): OcrPage?

    /** Stops a [read] in progress as soon as Tesseract allows; that read returns null. Safe from any thread. */
    fun cancel()
}
