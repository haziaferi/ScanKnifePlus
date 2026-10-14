package com.paperknifeplus.app.ui.components

import androidx.compose.ui.graphics.Color
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.text.DateFormat
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale

/**
 * One line the Page Numbers tool stamps on every page, a page number or a header or footer text: [format] with the tokens of
 * [expandPageTokens], in a corner [position] ("Top Left" to "Bottom Right", see [pageNumberPlacement]), [fontSize] points, in [color].
 */
data class PageNumbering(val format: String, val position: String, val fontSize: Float, val color: Color)

/** The font the Page Numbers tool writes with; it only encodes WinAnsi characters. */
private val PAGE_TEXT_FONT: PDFont = PDType1Font.HELVETICA_BOLD

/** [format] with `{n}` or `{page}` replaced by the 1-based [page], `{total}` by the document's [total] pages and `{date}` by [date]. */
fun expandPageTokens(format: String, page: Int, total: Int, date: String): String = format
    .replace("{n}", page.toString())
    .replace("{page}", page.toString())
    .replace("{total}", total.toString())
    .replace("{date}", date)

/**
 * Today's date as [locale]'s short date, in the Gregorian calendar whatever the locale's own, for `{date}`. Digits become ASCII and any
 * character the Helvetica of [applyPageNumbers] cannot encode is dropped, so a locale's date never makes the stamping fail.
 */
fun pageDate(now: Date = Date(), locale: Locale = Locale.getDefault()): String {
    val format = DateFormat.getDateInstance(DateFormat.SHORT, locale)
    format.calendar = GregorianCalendar(locale)
    return printableDate(format.format(now))
}

/**
 * [date] with ASCII digits and without the characters Helvetica cannot encode. The spaces and punctuation that only framed a dropped
 * character go with it, so "14.10.26 г." becomes "14.10.26", while a dropped character between two numbers leaves a space.
 */
internal fun printableDate(date: String): String {
    val marked = buildString {
        var i = 0
        while (i < date.length) {
            val codePoint = date.codePointAt(i)
            val digit = Character.digit(codePoint, 10)
            when {
                digit >= 0 -> append('0' + digit)
                encodes(String(Character.toChars(codePoint))) -> appendCodePoint(codePoint)
                else -> append(DROPPED)
            }
            i += Character.charCount(codePoint)
        }
    }
    return marked
        .replace(Regex(" *$DROPPED+ *"), "$DROPPED")
        .replace(Regex("^$DROPPED[.,]?|$DROPPED[.,]?$"), "")
        .replace(Regex("([.,/-])$DROPPED\\1"), "$1")
        .replace(Regex("(?<=[0-9])$DROPPED(?=[0-9])"), " ")
        .replace("$DROPPED", "")
        .trim()
}

/** Stands in for a dropped character while [printableDate] tidies what was around it. */
private const val DROPPED = '\u0000'

private fun encodes(text: String): Boolean = try {
    PAGE_TEXT_FONT.encode(text)
    true
} catch (e: IllegalArgumentException) {
    false
}

/**
 * Stamps each of [lines] on every page of [document], its tokens expanded with the page, the page count and [date]; a rotated page gets
 * its text in the corner as seen, reading upright. Throws IllegalArgumentException when a line has a character Helvetica cannot encode.
 */
fun applyPageNumbers(document: PDDocument, vararg lines: PageNumbering, date: String = pageDate()) {
    val total = document.numberOfPages
    document.pages.forEachIndexed { pageIdx, page ->
        val box = PageBox.of(page)
        document.appendTo(page).use { cs ->
            for (line in lines) {
                val text = expandPageTokens(line.format, pageIdx + 1, total, date)
                val textWidth = PAGE_TEXT_FONT.getStringWidth(text) / 1000 * line.fontSize
                val placement = pageNumberPlacement(line.position, textWidth, line.fontSize, box)
                cs.beginText()
                cs.setFont(PAGE_TEXT_FONT, line.fontSize)
                cs.setNonStrokingColor(line.color.red, line.color.green, line.color.blue)
                cs.setTextMatrix(placement.matrix())
                cs.showText(text)
                cs.endText()
            }
        }
    }
}
