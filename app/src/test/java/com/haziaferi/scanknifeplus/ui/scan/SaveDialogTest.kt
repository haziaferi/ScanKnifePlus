package com.haziaferi.scanknifeplus.ui.scan

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.testing.assertOneChoice
import com.paperknifeplus.app.testing.sameColour
import com.paperknifeplus.app.testing.segment
import com.paperknifeplus.app.ui.theme.AppPalette
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.paperknifeplus.app.ui.theme.ThemeMode
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The Save dialog: its choices, their colours, and the Make searchable options, shown only while the switch is on, from what the caller remembered. */
@RunWith(RobolectricTestRunner::class)
class SaveDialogTest {
    @get:Rule(order = 0)
    val pump = ComposeSnapshotPump()

    @get:Rule(order = 1)
    val rule = createComposeRule()

    private var saved: SaveOptions? = null

    private fun show(searchable: Boolean = true, txt: Boolean = false, language: OcrLanguage = OcrLanguage.ENGLISH) {
        rule.setContent {
            PaperKnifePlusTheme(themeMode = ThemeMode.LIGHT) {
                SaveDialog(onSave = { saved = it }, onDismiss = {}, searchable = searchable, txt = txt, language = language)
            }
        }
    }

    private fun save() = rule.onNodeWithText("Save scan").performClick()

    /** V2: a choice's heading sits nearer its own control than the group above it, so it reads as that control's. */
    @Test
    fun `each choice's heading sits nearer its control than the group above`() {
        show()
        // Unclipped (position and size, not boundsInRoot): the dialog scrolls, and a node outside its viewport reports a cut-off box.
        fun bounds(text: String) = rule.onNodeWithText(text).fetchSemanticsNode().let { Rect(it.positionInRoot, it.size.toSize()) }
        for ((above, heading, first) in listOf(Triple("Make searchable", "Language", "English"), Triple("English", "Reading", "Automatic"))) {
            val toControl = bounds(first).top - bounds(heading).bottom
            val fromAbove = bounds(heading).top - bounds(above).bottom
            assertTrue("$heading: $toControl px to its control, $fromAbove px from $above", toControl < fromAbove)
        }
    }

    @Test
    fun `Make searchable on shows the language, the reading and the txt switch, defaulting to Automatic`() {
        show()
        rule.onNodeWithText("Make searchable").performScrollTo().assertIsOn()
        rule.onNodeWithText("Adds the recognised text so you can search and copy it").assertExists()
        for (label in listOf("Language", "English", "Italian", "Reading", "Automatic", "Tables", "Receipts")) {
            rule.onNodeWithText(label).performScrollTo().assertExists()
        }
        rule.onNodeWithText("English").assertIsSelected()
        rule.onNodeWithText("Automatic").assertIsSelected()
        rule.onNodeWithText("Also save the text as .txt").performScrollTo().assertIsOff()
        save()
        assertEquals(SaveOptions(PdfPageSize.A4, "", TextOptions(OcrLanguage.ENGLISH, OcrReading.AUTOMATIC, txt = false)), saved)
    }

    @Test
    fun `turning Make searchable off hides its options and saves without text`() {
        show()
        rule.onNodeWithText("Make searchable").performScrollTo().performClick().assertIsOff()
        for (label in listOf("Language", "Reading", "Receipts", "Also save the text as .txt")) rule.onNodeWithText(label).assertDoesNotExist()
        save()
        assertNull(saved!!.text)
    }

    @Test
    fun `the dialog starts from the remembered switches and the given language, and saves the choices`() {
        show(searchable = false, txt = true, language = OcrLanguage.ITALIAN)
        rule.onNodeWithText("Language").assertDoesNotExist()
        rule.onNodeWithText("Make searchable").performScrollTo().performClick()
        rule.onNodeWithText("Italian").performScrollTo().assertIsSelected()
        rule.onNodeWithText("Also save the text as .txt").performScrollTo().assertIsOn()
        rule.onNodeWithText("Tables").performScrollTo().performClick()
        save()
        assertEquals(TextOptions(OcrLanguage.ITALIAN, OcrReading.TABLES, txt = true), saved!!.text)
    }

    @Test
    fun `page size, language and reading are each one choice, and the picked ones are saved`() {
        show()
        rule.assertOneChoice(listOf("A4", "Letter", "Legal", "Fit"))
        rule.assertOneChoice(listOf("English", "Italian"))
        rule.assertOneChoice(listOf("Automatic", "Tables", "Receipts"))
        rule.segment("A4").assertIsSelected()
        rule.segment("Letter").performScrollTo().performClick().assertIsSelected()
        rule.segment("A4").assertIsNotSelected()
        rule.segment("Italian").performScrollTo().performClick().assertIsSelected()
        rule.segment("Receipts").performScrollTo().performClick().assertIsSelected()
        save()
        assertEquals(SaveOptions(PdfPageSize.LETTER, "", TextOptions(OcrLanguage.ITALIAN, OcrReading.RECEIPTS, txt = false)), saved)
    }

    /**
     * The dialog in [palette]'s [theme]: its segments draw from the scheme the palette maps to (ScanPaletteTest holds those colours to their
     * contrast), the selected one on the tonal fill acSoft and the others on the dialog's surface sf.
     */
    private fun assertSegmentFills(palette: AppPalette, theme: ThemeMode) {
        rule.setContent { PaperKnifePlusTheme(palette, theme) { SaveDialog(onSave = {}, onDismiss = {}) } }
        val tokens = palette.tokens(oled = theme == ThemeMode.OLED)
        // 4 dp in from the start, half way down: inside the frame's border, short of the check and the label.
        fun fill(label: String): Color {
            val pixels = rule.segment(label).captureToImage().toPixelMap()
            return pixels[with(rule.density) { 4.dp.roundToPx() }, pixels.height / 2]
        }
        assertTrue("the selected segment's fill is acSoft, not ${fill("A4")}", sameColour(tokens.acSoft, fill("A4")))
        assertTrue("an unselected segment shows the dialog's surface, not ${fill("Letter")}", sameColour(tokens.sf, fill("Letter")))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Drawn, so the fill can be read.
    fun `in the Light theme the segments draw in the palette's colours`() = assertSegmentFills(AppPalette.ROSE, ThemeMode.LIGHT)

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `in the OLED theme the segments draw in the palette's colours`() = assertSegmentFills(AppPalette.STILL_WATER, ThemeMode.OLED)

    @Test
    fun `the language follows the phone, Italian for any Italian locale and English otherwise`() {
        assertEquals(OcrLanguage.ITALIAN, defaultOcrLanguage(Locale.ITALY))
        assertEquals(OcrLanguage.ITALIAN, defaultOcrLanguage(Locale("it", "CH")))
        assertEquals(OcrLanguage.ENGLISH, defaultOcrLanguage(Locale.US))
        assertEquals(OcrLanguage.ENGLISH, defaultOcrLanguage(Locale.FRANCE))
    }
}
