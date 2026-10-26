package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InvisibleTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    /** A Helvetica line showing [word] at height [y] in rendering mode [mode] (none set when null). */
    private fun line(word: String, y: Int, mode: Int? = null) = "BT ${mode?.let { "$it Tr " }.orEmpty()}/F1 12 Tf 72 $y Td ($word) Tj ET"

    private fun stripped(vararg streams: String): Pair<Boolean, PDDocument> {
        val doc = PDDocument.load(TestPdfs.layered(*streams))
        val removed = stripInvisibleText(doc, doc.getPage(0))
        // Saved and loaded again, so the test reads what a file would hold.
        val bytes = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        doc.close()
        return removed to PDDocument.load(bytes)
    }

    private fun PDDocument.text(): String = PDFTextStripper().getText(this)

    private fun PDDocument.words(): List<String> = text().split(Regex("""\s+""")).filter { it.isNotEmpty() }

    /** The page's content as "operands operator" strings, one per operator. */
    private fun PDDocument.ops(): List<String> {
        val tokens = PDFStreamParser(getPage(0).contents.use { it.readBytes() }).run {
            parse()
            tokens
        }
        val out = mutableListOf<String>()
        val operands = mutableListOf<String>()
        for (token in tokens) {
            if (token is Operator) {
                out += (operands + token.name).joinToString(" ")
                operands.clear()
            } else {
                operands += token.toString()
            }
        }
        return out
    }

    private fun List<String>.withoutShows() = filterNot { op -> listOf("Tj", "TJ", "'", "\"").any { op.endsWith(" $it") } }

    @Test
    fun `invisible text goes, while the image, the visible text and every other operator stay`() {
        val content = arrayOf("q 100 0 0 100 72 72 cm /Im1 Do Q", line("VISIBLE", 700), line("OLDOCR", 650, mode = 3))
        val original = PDDocument.load(TestPdfs.layered(*content)).use { it.ops() }
        val (removed, doc) = stripped(*content)
        doc.use {
            assertTrue(removed)
            assertEquals(listOf("VISIBLE"), it.words())
            assertEquals(original.withoutShows(), it.ops().withoutShows())
            assertEquals(original.filter { op -> "VISIBLE" in op }, it.ops().filter { op -> op.endsWith(" Tj") })
            val image = it.getPage(0).resources.getXObject(TestPdfs.LAYERED_IMAGE).cosObject as COSStream
            assertTrue(byteArrayOf(0, 64, -128, -1).contentEquals(image.createInputStream().use { s -> s.readBytes() }))
        }
    }

    @Test
    fun `text shown in every mode but 3 is never removed, mode 7 included`() {
        val visible = listOf(0, 1, 2, 4, 5, 6, 7).map { "MODE$it" }
        val lines = listOf(0, 1, 2, 4, 5, 6, 7).mapIndexed { i, mode -> line("MODE$mode", 700 - 20 * i, mode) }
        val (removed, doc) = stripped(*(lines + line("HIDDEN", 500, mode = 3)).toTypedArray())
        doc.use {
            assertTrue(removed)
            assertEquals(visible, it.words())
        }
        val (none, again) = stripped(line("SEVEN", 700, mode = 7))
        again.use {
            assertFalse(none)
            assertEquals(listOf("SEVEN"), it.words())
        }
    }

    @Test
    fun `a mode set inside q and Q only covers the text inside`() {
        val (removed, doc) = stripped("q ${line("INSIDE", 700, mode = 3)} Q", line("AFTER", 650))
        doc.use {
            assertTrue(removed)
            assertEquals(listOf("AFTER"), it.words())
        }
        val (_, other) = stripped("3 Tr q ${line("SHOWN", 700, mode = 0)} Q", line("HIDDEN", 650))
        other.use { assertEquals(listOf("SHOWN"), it.words()) }
    }

    @Test
    fun `the mode carries over from one content stream to the next`() {
        val (removed, doc) = stripped("3 Tr", line("HIDDEN", 700), "0 Tr", line("SHOWN", 650))
        doc.use {
            assertTrue(removed)
            assertEquals(listOf("SHOWN"), it.words())
        }
    }

    /** The glyphs [doc] shows, as PDFBox extracts them. */
    private fun glyphs(doc: PDDocument): List<TextPosition> {
        val found = mutableListOf<TextPosition>()
        object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: List<TextPosition>) {
                found += textPositions
            }
        }.getText(doc)
        return found
    }

    @Test
    fun `visible text keeps its place, so invisible text it follows on a line stays and quote operators keep their line moves`() {
        // SHOWN starts where HIDE's advance left it, so HIDE stays.
        val (removed, doc) = stripped("BT /F1 12 Tf 72 700 Td 3 Tr (HIDE) Tj 0 Tr (SHOWN) Tj ET")
        doc.use {
            assertFalse(removed)
            assertEquals(listOf("HIDESHOWN"), it.words())
        }
        val (after, tail) = stripped("BT /F1 12 Tf 72 700 Td (SHOWN) Tj 3 Tr (HIDE) Tj ET")
        tail.use {
            assertTrue(after)
            assertEquals(listOf("SHOWN"), it.words())
        }
        // ' and " move to the next line, and " sets the spacing, before they show; the visible line below them must not move.
        val quoted = "BT /F1 12 Tf 14 TL 72 700 Td 3 Tr (A) ' 3 2 (B) \" 0 Tr 0 -14 Td (CD) Tj ET"
        val before = PDDocument.load(TestPdfs.layered(quoted)).use { d -> glyphs(d).filter { it.unicode != "A" && it.unicode != "B" } }
        val (quotes, moved) = stripped(quoted)
        moved.use {
            assertTrue(quotes)
            val now = glyphs(it)
            assertEquals(listOf("C", "D"), now.map { g -> g.unicode })
            assertEquals(before.map { g -> g.xDirAdj to g.yDirAdj }, now.map { g -> g.xDirAdj to g.yDirAdj })
        }
    }

    @Test
    fun `type 3 text is kept in any mode`() {
        val (removed, doc) = stripped("BT 3 Tr /F3 12 Tf 72 700 Td (X) Tj ET")
        doc.use {
            assertFalse(removed)
            assertTrue(it.ops().any { op -> op.endsWith(" Tj") })
        }
    }

    @Test
    fun `a page whose content cannot be read is left as it was`() {
        val doc = PDDocument.load(TestPdfs.layered(line("OLDOCR", 700, mode = 3)))
        doc.use {
            val stream = (it.getPage(0).cosObject.getDictionaryObject(COSName.CONTENTS) as COSArray).getObject(0) as COSStream
            stream.setItem(COSName.FILTER, COSName.getPDFName("Bogus"))
            assertFalse(stripInvisibleText(it, it.getPage(0)))
            assertTrue(it.getPage(0).cosObject.getDictionaryObject(COSName.CONTENTS) is COSArray)
            assertEquals(COSName.getPDFName("Bogus"), stream.getDictionaryObject(COSName.FILTER))
        }
    }
}
