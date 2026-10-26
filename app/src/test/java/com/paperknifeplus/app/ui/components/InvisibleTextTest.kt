package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
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

    private fun stripped(vararg streams: String): Pair<Boolean, PDDocument> = strippedPage(TestPdfs.layered(*streams))

    /** [pdf] with its first page stripped, saved and loaded again. */
    private fun strippedPage(pdf: ByteArray): Pair<Boolean, PDDocument> {
        val doc = PDDocument.load(pdf)
        val removed = InvisibleTextStripper(doc).strip(0)
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
            assertFalse(InvisibleTextStripper(it).strip(0))
            assertTrue(it.getPage(0).cosObject.getDictionaryObject(COSName.CONTENTS) is COSArray)
            assertEquals(COSName.getPDFName("Bogus"), stream.getDictionaryObject(COSName.FILTER))
        }
    }

    /** The decoded content of form [name] in page [page]'s resources. */
    private fun PDDocument.form(name: String, page: Int = 0): String {
        val form = getPage(page).resources.getXObject(COSName.getPDFName(name)).cosObject as COSStream
        return String(form.createInputStream().use { it.readBytes() }, Charsets.ISO_8859_1)
    }

    @Test
    fun `a nested form only this page draws loses its invisible text, and the form around it stays as it is`() {
        val pdf = TestPdfs.withForms(mapOf("Fx1" to "q /Fx2 Do Q", "Fx2" to line("OLDOCR", 650, mode = 3)), "/Fx1 Do " + line("VISIBLE", 700))
        val (removed, doc) = strippedPage(pdf)
        doc.use {
            assertTrue(removed)
            assertEquals(listOf("VISIBLE"), it.words())
            assertEquals("q /Fx2 Do Q", it.form("Fx1"))
            assertEquals("/Fx1 Do " + line("VISIBLE", 700), String(it.getPage(0).contents.readBytes(), Charsets.ISO_8859_1))
        }
    }

    /** The form page [page] draws as [name]. */
    private fun PDDocument.formStream(name: String, page: Int = 0): COSStream =
        getPage(page).resources.getXObject(COSName.getPDFName(name)).cosObject as COSStream

    private fun COSStream.content(): String = String(createInputStream().use { it.readBytes() }, Charsets.ISO_8859_1)

    /** Everything [value] holds, streams decoded, references followed except to parents and children; equal strings mean equal objects. */
    private fun describe(value: COSBase?): String = when (val v = if (value is COSObject) value.`object` else value) {
        is COSStream -> "stream" + entries(v) + "{" + v.content() + "}"
        is COSDictionary -> entries(v)
        is COSArray -> v.joinToString(",", "[", "]") { describe(it) }
        else -> v.toString()
    }

    private fun entries(dict: COSDictionary): String = (dict.keySet() - setOf(COSName.PARENT, COSName.P, COSName.KIDS)).sortedBy { it.name }
        .joinToString(" ", "<<", ">>") { "${it.name}=${describe(dict.getItem(it))}" }

    private fun PDDocument.pageText(page: Int): String = PDFTextStripper().apply {
        startPage = page + 1
        endPage = page + 1
    }.getText(this).trim()

    private fun PDDocument.formCount(): Int = document.objects.count { (it.`object` as? COSStream)?.getCOSName(COSName.SUBTYPE) == COSName.FORM }

    @Test
    fun `forms in a resources dictionary every page shares are stripped in place when only one page draws each`() {
        val forms = mapOf("Fx1" to line("OLDONE", 650, mode = 3), "Fx2" to line("OLDTWO", 650, mode = 3))
        val pdf = TestPdfs.sharedResources(forms, listOf("/Fx1 Do", "/Fx2 Do"))
        fun PDDocument.secondPage() = describe(getPage(1).cosObject.getItem(COSName.CONTENTS)) + describe(formStream("Fx2", page = 1))
        val (secondPage, objects) = PDDocument.load(pdf).use {
            assertTrue(it.getPage(0).resources.cosObject === it.getPage(1).resources.cosObject)
            it.secondPage() to it.document.objects.size
        }
        val (removed, doc) = strippedPage(pdf)
        doc.use {
            assertTrue(removed)
            assertEquals("", it.pageText(0))
            assertEquals("OLDTWO", it.pageText(1))
            assertTrue(it.getPage(0).resources.cosObject === it.getPage(1).resources.cosObject)
            assertFalse("OLDONE" in it.formStream("Fx1").content())
            assertEquals(secondPage, it.secondPage())
            assertEquals(objects, it.document.objects.size)
        }
    }

    @Test
    fun `a form another page also draws is copied for the redone page, and only when it loses text`() {
        val forms = mapOf("Fx1" to line("OLDOCR", 650, mode = 3))
        val pdf = TestPdfs.withForms(forms, "/Fx1 Do", "/Fx1 Do")
        val secondPage = PDDocument.load(pdf).use { describe(it.getPage(1).cosObject) }
        val (removed, doc) = strippedPage(pdf)
        doc.use {
            assertTrue(removed)
            assertEquals("", it.pageText(0))
            assertEquals("OLDOCR", it.pageText(1))
            assertTrue(it.formStream("Fx1") !== it.formStream("Fx1", page = 1))
            assertEquals(secondPage, describe(it.getPage(1).cosObject))
            assertEquals(2, it.formCount())
        }
        PDDocument.load(TestPdfs.withForms(mapOf("Fx1" to line("SHOWN", 650)), "/Fx1 Do", "/Fx1 Do")).use {
            val resources = it.getPage(0).resources.cosObject
            assertFalse(InvisibleTextStripper(it).strip(0))
            assertTrue(resources === it.getPage(0).cosObject.getDictionaryObject(COSName.RESOURCES))
        }
    }

    @Test
    fun `a page that inherits its resources gets its own, and the page tree stays as it is`() {
        val pdf = TestPdfs.sharedResources(mapOf("Fx1" to line("OLDOCR", 650, mode = 3)), listOf("/Fx1 Do", "/Fx1 Do"), onPagesNode = true)
        val (tree, secondPage) = PDDocument.load(pdf).use {
            assertFalse(it.getPage(0).cosObject.containsKey(COSName.RESOURCES))
            describe(it.pages.cosObject) to describe(it.getPage(1).cosObject)
        }
        val (removed, doc) = strippedPage(pdf)
        doc.use {
            assertTrue(removed)
            assertEquals("", it.pageText(0))
            assertEquals("OLDOCR", it.pageText(1))
            assertTrue(it.getPage(0).cosObject.containsKey(COSName.RESOURCES))
            assertEquals(tree, describe(it.pages.cosObject))
            assertEquals(secondPage, describe(it.getPage(1).cosObject))
        }
    }

    @Test
    fun `an annotation that draws the form keeps the original`() {
        val annotated = PDDocument.load(TestPdfs.withForms(mapOf("Fx1" to line("OLDOCR", 650, mode = 3)), "/Fx1 Do")).use { d ->
            val page = d.getPage(0)
            page.annotations = listOf(PDAnnotationLink().apply {
                rectangle = PDRectangle(72f, 72f, 100f, 20f)
                cosObject.setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, d.formStream("Fx1")) })
            })
            ByteArrayOutputStream().also { d.save(it) }.toByteArray()
        }
        val (removed, doc) = strippedPage(annotated)
        doc.use {
            assertTrue(removed)
            assertFalse("OLDOCR" in it.formStream("Fx1").content())
            val appearance = it.getPage(0).annotations.single().cosObject.getCOSDictionary(COSName.AP).getDictionaryObject(COSName.N) as COSStream
            assertEquals(line("OLDOCR", 650, mode = 3), appearance.content())
        }
    }

    @Test
    fun `a shared form around this page's old layer is copied with it, so only this page's path changes`() {
        val pdf = TestPdfs.withForms(mapOf("Fx1" to "q /Fx2 Do Q", "Fx2" to line("OLDOCR", 650, mode = 3)), "/Fx1 Do", "/Fx1 Do")
        val secondPage = PDDocument.load(pdf).use { describe(it.getPage(1).cosObject) }
        val (removed, doc) = strippedPage(pdf)
        doc.use {
            assertTrue(removed)
            assertEquals("", it.pageText(0))
            assertEquals("OLDOCR", it.pageText(1))
            val outer = it.formStream("Fx1")
            assertTrue(outer !== it.formStream("Fx1", page = 1))
            assertEquals("q /Fx2 Do Q", outer.content())
            assertEquals(secondPage, describe(it.getPage(1).cosObject))
            assertEquals(4, it.formCount())
        }
    }

    @Test
    fun `a shared form without resources draws each page's own inner form, which is stripped in place`() {
        val pdf = PDDocument.load(TestPdfs.withForms(mapOf("Fx1" to "q /Fx2 Do Q", "Fx2" to line("OLDONE", 650, mode = 3)), "/Fx1 Do", "/Fx1 Do"))
            .use { d ->
                val outer = d.formStream("Fx1")
                val inner = (outer.getDictionaryObject(COSName.RESOURCES) as COSDictionary).getCOSDictionary(COSName.XOBJECT)
                    .getDictionaryObject(COSName.getPDFName("Fx2")) as COSStream
                val other = d.document.createCOSStream().apply {
                    inner.entrySet().forEach { (key, value) -> setItem(key, value) }
                    createOutputStream().use { it.write(line("OLDTWO", 650, mode = 3).toByteArray()) }
                }
                outer.removeItem(COSName.RESOURCES)
                listOf(inner, other).forEachIndexed { page, form ->
                    d.getPage(page).resources.cosObject.getCOSDictionary(COSName.XOBJECT).setItem(COSName.getPDFName("Fx2"), form)
                }
                ByteArrayOutputStream().also { d.save(it) }.toByteArray()
            }
        val (secondPage, forms) = PDDocument.load(pdf).use { describe(it.getPage(1).cosObject) to it.formCount() }
        val (removed, doc) = strippedPage(pdf)
        doc.use {
            assertTrue(removed)
            assertEquals("", it.pageText(0))
            assertEquals("OLDTWO", it.pageText(1))
            assertTrue(it.formStream("Fx1") === it.formStream("Fx1", page = 1))
            assertEquals(secondPage, describe(it.getPage(1).cosObject))
            assertEquals(forms, it.formCount())
        }
    }

    @Test
    fun `a page whose content cannot be read counts as drawing every form it reaches, so they are copied`() {
        val forms = mapOf("Fx1" to line("OLDONE", 650, mode = 3), "Fx2" to line("OLDTWO", 650, mode = 3))
        PDDocument.load(TestPdfs.sharedResources(forms, listOf("/Fx1 Do", "/Fx2 Do"))).use {
            val shared = it.getPage(1).resources.cosObject
            val original = it.formStream("Fx1")
            (it.getPage(1).cosObject.getDictionaryObject(COSName.CONTENTS) as COSStream).setItem(COSName.FILTER, COSName.getPDFName("Bogus"))
            assertTrue(InvisibleTextStripper(it).strip(0))
            assertTrue(it.formStream("Fx1") !== original)
            assertFalse("OLDONE" in it.formStream("Fx1").content())
            assertEquals(line("OLDONE", 650, mode = 3), original.content())
            assertTrue(shared === it.getPage(1).resources.cosObject)
        }
    }

    @Test
    fun `once a page draws its copy, the other page that drew the form strips it in place`() {
        PDDocument.load(TestPdfs.withForms(mapOf("Fx1" to line("OLDOCR", 650, mode = 3)), "/Fx1 Do", "/Fx1 Do")).use {
            val original = it.formStream("Fx1")
            val stripper = InvisibleTextStripper(it)
            assertTrue(stripper.strip(0))
            assertTrue(stripper.strip(1))
            assertTrue(it.formStream("Fx1") !== original)
            assertTrue(it.formStream("Fx1", page = 1) === original)
            assertEquals("", it.text().trim())
        }
    }

    @Test
    fun `a form inherits the rendering mode its caller draws it in`() {
        val forms = mapOf("Fx1" to "BT /F1 12 Tf 72 650 Td (INHERITED) Tj ET")
        val (hidden, doc) = strippedPage(TestPdfs.withForms(forms, "3 Tr /Fx1 Do"))
        doc.use {
            assertTrue(hidden)
            assertEquals(emptyList<String>(), it.words())
        }
        for (page in listOf("0 Tr /Fx1 Do", "q 3 Tr Q /Fx1 Do", "3 Tr /Fx1 Do 0 Tr /Fx1 Do")) {
            val (removed, kept) = strippedPage(TestPdfs.withForms(forms, page))
            kept.use {
                assertFalse(page, removed)
                assertEquals(page, forms.getValue("Fx1"), it.form("Fx1"))
            }
        }
    }

    @Test
    fun `a form that draws itself is stripped once, and an unreadable form stays as it was`() {
        val (removed, doc) = strippedPage(TestPdfs.withForms(mapOf("Fx1" to "/Fx1 Do " + line("OLDOCR", 650, mode = 3)), "/Fx1 Do"))
        doc.use {
            assertTrue(removed)
            assertFalse("OLDOCR" in it.form("Fx1"))
        }
        val broken = PDDocument.load(TestPdfs.withForms(mapOf("Fx1" to line("OLDFORM", 650, mode = 3)), "/Fx1 Do " + line("OLDPAGE", 700, mode = 3)))
        broken.use {
            val form = it.getPage(0).resources.getXObject(COSName.getPDFName("Fx1")).cosObject as COSStream
            form.setItem(COSName.FILTER, COSName.getPDFName("Bogus"))
            assertTrue(InvisibleTextStripper(it).strip(0))
            val content = String(it.getPage(0).contents.readBytes(), Charsets.ISO_8859_1)
            assertTrue(content, "/Fx1 Do" in content && "OLDPAGE" !in content)
            assertEquals(COSName.getPDFName("Bogus"), form.getDictionaryObject(COSName.FILTER))
        }
    }
}
