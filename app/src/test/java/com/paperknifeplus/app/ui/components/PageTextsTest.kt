package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale

/** Page Numbers expands {n}, {total} and {date}, and stamps header and footer lines together. */
@RunWith(RobolectricTestRunner::class)
class PageTextsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private val day = GregorianCalendar(2026, Calendar.OCTOBER, 14, 12, 0).time

    @Test
    fun `tokens are replaced wherever they appear and unknown braces stay`() {
        assertEquals("Page 2 of 7 - 14/10/26 {x} 2", expandPageTokens("Page {n} of {total} - {date} {x} {n}", 2, 7, "14/10/26"))
        assertEquals("3 / 3 of 7", expandPageTokens("{page} / {n} of {total}", 3, 7, "today"))
    }

    @Test
    fun `the date is the locale's short date in the Gregorian calendar`() {
        assertEquals("10/14/26", pageDate(day, Locale.US))
        val thai = pageDate(day, Locale.forLanguageTag("th-TH"))
        // The Buddhist calendar would give 2569 (69).
        assertTrue(thai, thai.endsWith("26") && !thai.contains("69"))
    }

    @Test
    fun `a date the font cannot show is reduced to ASCII digits and the characters it can`() {
        val arabic = pageDate(day, Locale.forLanguageTag("ar-EG"))
        assertTrue(arabic, arabic.all { it.code < 0x80 } && arabic.contains("14") && arabic.contains("10"))
        val bulgarian = pageDate(day, Locale.forLanguageTag("bg-BG"))
        assertTrue(bulgarian, bulgarian.all { it.code < 0x100 } && bulgarian.contains("14"))
    }

    @Test
    fun `separators that only framed a dropped character go with it`() {
        assertEquals("14.10.26", printableDate("14.10.26 г."))
        assertEquals("14/10/2026", printableDate("١٤‏/١٠‏/٢٠٢٦"))
        assertEquals("14.10.26", printableDate("14.г.10.26"))
        assertEquals("2026 10 14", printableDate("2026年10月14日"))
        assertEquals("26. 10. 14.", printableDate("26. 10. 14."))
    }

    @Test
    fun `header and footer lines are stamped on every page with the page count and date`() {
        val bytes = PDDocument.load(TestPdfs.plain(3)).use { doc ->
            applyPageNumbers(
                doc,
                PageNumbering("Report {date}", "Top Center", 12f, Color.Black),
                PageNumbering("Page {n} of {total}", "Bottom Right", 12f, Color.Black),
                date = "14/10/26"
            )
            ByteArrayOutputStream().also(doc::save).toByteArray()
        }
        PDDocument.load(bytes).use { doc ->
            for (page in 1..3) {
                val text = PDFTextStripper().apply { startPage = page; endPage = page }.getText(doc)
                assertTrue(text, text.contains("Report 14/10/26"))
                assertTrue(text, text.contains("Page $page of 3"))
            }
        }
    }
}
