package com.haziaferi.scanknifeplus.scan.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.ocr.OcrPage
import com.haziaferi.scanknifeplus.ocr.OcrWord
import com.haziaferi.scanknifeplus.scanner.export.PdfLayout
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The real pdfbox assembler, with and without a password and a text layer, with Robolectric's native graphics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfBoxAssemblerTest {
    private val dir: File = Files.createTempDirectory("assembler").toFile()
    private val pages = List(2) { i ->
        File(dir, "$i.jpg").also { f ->
            val b = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 100, 50)) }
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            b.recycle()
        }
    }

    @Before
    fun setUp() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun write(password: String?): File = File(dir, "out-${password ?: "plain"}.pdf").also {
        assertTrue(PdfBoxAssembler.write(pages, PdfPageSize.A4, dir, it, password, emptyList()))
    }

    @Test
    fun `without a password the PDF is not encrypted`() {
        PDDocument.load(write(null)).use { doc ->
            assertFalse(doc.isEncrypted)
            assertNull(doc.encryption)
            assertEquals(2, doc.numberOfPages)
        }
    }

    @Test
    fun `the PDF carries no Creator or Producer`() {
        PDDocument.load(write(null)).use { doc ->
            assertNull(doc.documentInformation.creator)
            assertNull(doc.documentInformation.producer)
        }
    }

    @Test
    fun `with a password the PDF is AES-256 as Protect writes it and opens only with that password`() {
        val pdf = write("secret")
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(pdf).close() }
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(pdf, "Secret").close() }
        PDDocument.load(pdf, "secret").use { doc ->
            assertTrue(doc.isEncrypted)
            assertEquals(5, doc.encryption.version)
            assertEquals(6, doc.encryption.revision)
            assertEquals(256, doc.encryption.length)
            assertTrue(doc.currentAccessPermission.isOwnerPermission) // the same password is the owner password
            assertEquals(2, doc.numberOfPages)
        }
    }

    @Test
    fun `a password SASLprep rejects fails the PDF, and isUsablePassword tells beforehand`() {
        val rejected = "pass\u0007word" // an ASCII control character, which SASLprep prohibits
        assertFalse(PdfBoxAssembler.write(pages, PdfPageSize.A4, dir, File(dir, "rejected.pdf"), rejected, emptyList()))
        assertFalse(ScanExport.isUsablePassword(rejected))
        assertFalse(ScanExport.isUsablePassword("")) // private use
        for (usable in listOf("secret", "пароль", "", null)) assertTrue("$usable", ScanExport.isUsablePassword(usable))
    }

    @Test
    fun `a non-Latin password round-trips`() {
        PDDocument.load(write("пароль"), "пароль").use { assertEquals(2, it.numberOfPages) }
    }

    @Test
    fun `Fit gives each page its image's shape with A4's long edge, the image filling it`() {
        val landscape = File(dir, "wide.jpg").also { f ->
            val b = Bitmap.createBitmap(120, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            b.recycle()
        }
        val pdf = File(dir, "fit.pdf")
        assertTrue(PdfBoxAssembler.write(listOf(pages[0], landscape), PdfPageSize.FIT, dir, pdf, null, emptyList()))
        PDDocument.load(pdf).use { doc ->
            val long = PdfPageSize.A4.height.toFloat()
            val boxes = doc.pages.map { it.mediaBox.width to it.mediaBox.height }
            assertEquals(long * 60 / 80, boxes[0].first, 0.01f)
            assertEquals(long, boxes[0].second, 0.01f)
            assertEquals(long, boxes[1].first, 0.01f)
            assertEquals(long / 3, boxes[1].second, 0.01f)
        }
    }

    /** A line of text as PDFTextStripper extracts it, with its first glyph's left edge, its last glyph's right edge and its baseline. */
    private data class Line(val text: String, val left: Float, val right: Float, val baseline: Float)

    private fun lines(pdf: File, password: String? = null): List<Line> {
        val found = mutableListOf<Line>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                val last = positions.last()
                found += Line(text, positions.first().xDirAdj, last.xDirAdj + last.widthDirAdj, positions.first().yDirAdj)
                super.writeString(text, positions)
            }
        }
        (if (password == null) PDDocument.load(pdf) else PDDocument.load(pdf, password)).use { stripper.getText(it) }
        return found
    }

    private fun word(text: String, left: Float, top: Float, right: Float, bottom: Float, line: Int) =
        OcrWord(text, left, top, right, bottom, 90f, 0, 0, line)

    // Words on lines of their own, so the stripper reports each word with its own position.
    private val read = OcrPage(
        listOf(
            word("Hello", 0.1f, 0.1f, 0.5f, 0.15f, 0),
            word("€12,50", 0.5f, 0.6f, 0.9f, 0.64f, 1),
            word("漢字", 0.1f, 0.7f, 0.3f, 0.75f, 2),
            word("after", 0.2f, 0.8f, 0.4f, 0.84f, 3),
        ),
    )

    private fun withText(password: String?, size: PdfPageSize = PdfPageSize.A4): File = File(dir, "text-${password ?: "plain"}.pdf").also {
        assertTrue(PdfBoxAssembler.write(pages, size, dir, it, password, listOf(read)))
    }

    /** Asserts that [word] was found in [found] where its box falls on a page with the image drawn as A4 places a 60 x 80 page. */
    private fun assertPlaced(word: OcrWord, found: List<Line>) {
        val line = found.single { it.text == word.text }
        val image = PdfLayout.imageRect(PdfPageSize.A4, 60, 80)
        val pageHeight = PdfPageSize.A4.height
        val left = image.x + word.left * image.width
        val right = image.x + word.right * image.width
        // From the top of the page, as the stripper measures.
        val top = pageHeight - (image.y + (1 - word.top) * image.height)
        val bottom = pageHeight - (image.y + (1 - word.bottom) * image.height)
        assertEquals("${word.text} left", left, line.left.toDouble(), 1.0)
        assertEquals("${word.text} spans its box", right, line.right.toDouble(), 2.0)
        assertTrue("${word.text} baseline ${line.baseline} in $top..$bottom", line.baseline in top.toFloat()..bottom.toFloat())
    }

    @Test
    fun `the text layer holds each word where its box is, euro included, and skips a word the font cannot show`() {
        val found = lines(withText(null))
        assertPlaced(read.words[0], found)
        assertPlaced(read.words[1], found)
        assertPlaced(read.words[3], found)
        assertTrue("Liberation Sans has no CJK", found.none { "漢" in it.text })
        PDDocument.load(withText(null)).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertEquals("only the first page has text", "", PDFTextStripper().apply { startPage = 2; endPage = 2 }.getText(doc).trim())
            val font = doc.getPage(0).resources.let { r -> r.getFont(r.fontNames.single()) }
            assertTrue("embedded as a subset", font.name.matches(Regex("[A-Z]{6}[+].*LiberationSans.*")))
        }
    }

    @Test
    fun `the text layer is there with a password too`() {
        val found = lines(withText("secret"), "secret")
        assertPlaced(read.words[0], found)
        assertPlaced(read.words[3], found)
    }

    @Test
    fun `the text is invisible`() {
        PDDocument.load(withText(null)).use { doc ->
            val content = doc.getPage(0).contents.bufferedReader(Charsets.ISO_8859_1).readText()
            assertTrue("rendering mode 3, neither fill nor stroke", Regex("(^|\\s)3 Tr").containsMatchIn(content))
        }
    }

    @Test
    fun `without text no font is added`() {
        PDDocument.load(write(null)).use { doc -> assertTrue(doc.pages.all { it.resources.fontNames.none() }) }
    }

    @Test
    fun `a page orientation detection turned keeps its words in their boxes, running up the page`() {
        // Turned a quarter clockwise to read: in the image the word runs bottom to top, in a tall box.
        val turned = OcrPage(listOf(word("Sideways", 0.4f, 0.2f, 0.45f, 0.6f, 0)), turnedClockwise = 90)
        val pdf = File(dir, "turned.pdf")
        assertTrue(PdfBoxAssembler.write(pages.take(1), PdfPageSize.FIT, dir, pdf, null, listOf(turned)))
        val glyphs = mutableListOf<TextPosition>()
        val stripper = object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                glyphs += text
                super.processTextPosition(text)
            }
        }
        // PDFTextStripper breaks text that is not horizontal into one line per glyph; readers that follow the text direction do not.
        val text = PDDocument.load(pdf).use { stripper.getText(it) }
        assertEquals("Sideways", text.filterNot { it.isWhitespace() })
        assertEquals(setOf(90f), glyphs.map { it.dir }.toSet())
        val ys = glyphs.map { it.y }
        assertEquals("running up the page", ys.sortedDescending(), ys)
        val image = PdfLayout.imageRect(PdfPageSize.FIT, 60, 80)
        val pageHeight = image.height
        assertTrue(ys.first() <= pageHeight * 0.6 + 1 && ys.last() >= pageHeight * 0.2 - 1)
    }
}
