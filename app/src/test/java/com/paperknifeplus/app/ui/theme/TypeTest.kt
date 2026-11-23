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

    /** Type.kt's own interface styles; the wordmark's size is the logo's, given where it is drawn. */
    private val own = mapOf("CameraText" to CameraText)

    @Test
    fun `every Material slot and the app's own styles draw in Plus Jakarta Sans`() {
        for ((name, style) in slots + own + ("WordmarkText" to WordmarkText)) assertEquals(name, PlusJakartaSans, style.fontFamily)
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
     * No text sets its own size, line height, spacing or font, nor builds a TextStyle, outside Type.kt. The logo's size (Wordmark) is its
     * geometry, and a size computed from a document's (the page number as it will print) is not a literal, so neither counts.
     */
    @Test
    fun `no text style is set outside Type kt`() {
        val literal = Regex("""(fontSize|lineHeight|letterSpacing)\s*=\s*\(?-?[\d.]+\)?\.(sp|em)|fontFamily\s*=|(?<!\w)TextStyle\(""")
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offenders = main.walkTopDown().filter { it.extension == "kt" && it.name != "Type.kt" }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> literal.containsMatchIn(line) && !WORDMARK.containsMatchIn(line) }
                .map { (i, line) -> "${file.name}:${i + 1}: ${line.trim()}" }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private companion object {
        /** The logo: the wordmark's size beside its mark, and its raised "+". */
        val WORDMARK = Regex("""Wordmark(Row|Stack)\(fontSize = |SpanStyle\(.*fontSize = 0\.62\.em""")
    }
}
