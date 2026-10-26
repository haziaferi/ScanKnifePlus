package com.haziaferi.scanknifeplus.ui.scan

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.paperknifeplus.app.testing.ComposeSnapshotPump
import com.paperknifeplus.app.ui.theme.PaperKnifePlusTheme
import com.paperknifeplus.app.ui.theme.ThemeMode
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The Save dialog's Make searchable options: shown only while the switch is on, starting from what the caller remembered. */
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

    private fun save() = rule.onNodeWithText("Choose where to save").performClick()

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
    fun `the language follows the phone, Italian for any Italian locale and English otherwise`() {
        assertEquals(OcrLanguage.ITALIAN, defaultOcrLanguage(Locale.ITALY))
        assertEquals(OcrLanguage.ITALIAN, defaultOcrLanguage(Locale("it", "CH")))
        assertEquals(OcrLanguage.ENGLISH, defaultOcrLanguage(Locale.US))
        assertEquals(OcrLanguage.ENGLISH, defaultOcrLanguage(Locale.FRANCE))
    }
}
