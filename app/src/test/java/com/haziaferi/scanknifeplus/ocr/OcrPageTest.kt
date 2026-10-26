package com.haziaferi.scanknifeplus.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPageTest {
    private fun word(text: String, confidence: Float, block: Int = 0, paragraph: Int = 0, line: Int = 0) =
        OcrWord(text, 0f, 0f, 0.1f, 0.1f, confidence, block, paragraph, line)

    private val page = OcrPage(
        listOf(
            word("Total", 90f),
            word("12,50", 85f),
            word("€", 30f),
            word("Grazie", 95f, line = 1),
            word("Arrivederci", 92f, paragraph = 1, line = 2),
            word("Page", 88f, block = 1, paragraph = 2, line = 3),
        ),
    )

    @Test
    fun `text joins words with spaces, lines with breaks and blocks with an empty line`() {
        assertEquals("Total 12,50 €\nGrazie\nArrivederci\n\nPage", page.text())
    }

    @Test
    fun `text leaves out words below the confidence floor without stray separators`() {
        assertEquals("Total 12,50\nGrazie\nArrivederci\n\nPage", page.text(OcrPage.TEXT_FILE_MIN_CONFIDENCE))
        assertEquals("", OcrPage(listOf(word("faint", 10f))).text(40))
    }

    @Test
    fun `mean confidence below 55 flags the page as handwritten, an empty page is not`() {
        assertFalse(page.looksHandwritten)
        assertTrue(OcrPage(listOf(word("scrawl", 40f), word("notes", 60f))).looksHandwritten)
        assertFalse(OcrPage(emptyList()).looksHandwritten)
        assertEquals(0, OcrPage(emptyList()).meanConfidence)
    }

    @Test
    fun `the reading choices map to Tesseract's page segmentation modes`() {
        assertEquals(3, OcrReading.AUTOMATIC.pageSegMode)
        assertEquals(6, OcrReading.TABLES.pageSegMode)
        assertEquals(4, OcrReading.RECEIPTS.pageSegMode)
    }
}
