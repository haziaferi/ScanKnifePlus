package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class PdfToTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun withText(text: String): ByteArray = PDDocument().use { doc ->
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use {
            it.beginText()
            it.setFont(PDType1Font.HELVETICA, 12f)
            it.newLineAtOffset(72f, 700f)
            it.showText(text)
            it.endText()
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    @Test
    fun `a one-page PDF gives its text under a page header`() {
        val text = extractPdfText(withText("Hello text layer").inputStream(), null)
        assertTrue(text, text.startsWith("--- PAGE 1 ---\n"))
        assertTrue(text, text.contains("Hello text layer"))
    }

    @Test
    fun `bytes that are not a PDF throw`() {
        assertThrows(IOException::class.java) { extractPdfText(TestPdfs.garbage.inputStream(), null) }
    }

    @Test
    fun `a failed extraction is a failure result, not text`() = runBlocking {
        val garbage = TestPdfs.write(context.cacheDir, TestPdfs.garbage, "garbage.pdf")
        assertTrue(processText(context, garbage, null).isFailure)
        assertTrue(processText(context, TestPdfs.nullStreamUri(), null).exceptionOrNull() is IOException)
        val good = TestPdfs.write(context.cacheDir, withText("Readable"), "good.pdf")
        assertTrue(processText(context, good, null).getOrThrow().first.contains("Readable"))
    }

    @Test
    fun `the chunks joined give the text back`() {
        val text = (1..95).joinToString("\n") { "line $it" } + "\n\n--- PAGE 2 ---\nend"
        val chunks = textChunks(text, linesPerChunk = 10)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(10, chunks.size)
        assertTrue(chunks.dropLast(1).all { it.count { c -> c == '\n' } == 10 && it.endsWith("\n") })
        assertEquals(emptyList<String>(), textChunks("", 10))
        assertEquals(listOf("no break"), textChunks("no break", 10))
    }
}
