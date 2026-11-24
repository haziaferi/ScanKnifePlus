package com.paperknifeplus.app.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rule 1 of 2026-11-21: every text style comes from Type.kt, in Plus Jakarta Sans and at 12 sp or more. */
class TypeTest {
    private val slots: Map<String, TextStyle> = with(Typography) {
        mapOf(
            "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
            "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
            "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
            "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
            "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall
        )
    }

    /** Type.kt's own interface styles; the wordmark's size is the logo's, and a page number's the document's, given where they are drawn. */
    private val own = mapOf("CameraText" to CameraText)

    @Test
    fun `every Material slot and the app's own styles draw in Plus Jakarta Sans`() {
        for ((name, style) in slots + own + mapOf("WordmarkText" to WordmarkText, "PageNumberText" to PageNumberText)) {
            assertEquals(name, PlusJakartaSans, style.fontFamily)
        }
    }

    @Test
    fun `no style is under 12 sp, the document's text included`() {
        for ((name, style) in slots + own + ("DocumentText" to DocumentText)) {
            assertTrue("$name is in sp", style.fontSize.isSp)
            assertTrue("$name is ${style.fontSize}", style.fontSize.value >= 12f)
            assertTrue("$name has its line height", style.lineHeight.isSp && style.lineHeight.value >= style.fontSize.value)
        }
    }

    @Test
    fun `the extracted text alone is in monospace`() = assertEquals(FontFamily.Monospace, DocumentText.fontFamily)

    /**
     * No text sets its own size, line height, spacing or font, builds or provides a TextStyle, or makes a second scale, outside Type.kt; a
     * size on the line after its name counts. The logo's size (Wordmark) is its geometry, and a size computed from a document's (the page
     * number as it will print) is not a literal, so neither counts. Only the two screens that show a document's content mark text as it.
     */
    @Test
    fun `no text style is set outside Type kt`() {
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offenders = main.walkTopDown().filter { it.extension == "kt" && it.name != "Type.kt" }.flatMap { file ->
            val text = file.readText()
            val forbidden = if (file.name in CONTENT_SCREENS) STYLE else Regex("${STYLE.pattern}|$CONTENT_MARK")
            forbidden.findAll(text).map { match ->
                val start = text.lastIndexOf('\n', match.range.first) + 1
                val end = text.indexOf('\n', match.range.last).let { if (it < 0) text.length else it }
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                text.substring(start, end) to line
            }.filter { (lines, _) -> !WORDMARK.containsMatchIn(lines) }.map { (lines, line) -> "${file.name}:$line: ${lines.trim()}" }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private companion object {
        /** A style set outside the scale: a literal size, line height or spacing (on its line or the next), a font, a style made or provided. */
        val STYLE = Regex(
            """(fontSize|lineHeight|letterSpacing)\s*=\s*\(?-?[\d.]+\)?\.(sp|em)|fontFamily\s*=|(?<!\w)TextStyle\(|\.copy\([^()]*fontSize\s*=""" +
                """|(?<!\w)TextUnit\(|(?<!\w)ProvideTextStyle\(|LocalTextStyle\.current\.copy|(?<![\w.])Typography\("""
        )

        /** Marking text as a document's content. */
        const val CONTENT_MARK = """documentContent\("""

        /** The screens that show a document's own content, and so may mark text as it. */
        val CONTENT_SCREENS = setOf("PdfToTextView.kt", "PageNumbersView.kt")

        /** The logo: the wordmark's size beside its mark, and its raised "+". */
        val WORDMARK = Regex("""Wordmark(Row|Stack)\(fontSize = |SpanStyle\(.*fontSize = 0\.62\.em""")
    }
}
