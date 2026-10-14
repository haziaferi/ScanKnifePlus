package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
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
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Random
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RedactionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    // Read after PDFBoxResourceLoader.init, which the font needs.
    private val font: PDType1Font get() = PDType1Font.HELVETICA

    /** A small page showing each of [lines] (baseline y to text) at x = 36 in 12 pt Helvetica. */
    private fun textPage(doc: PDDocument, vararg lines: Pair<Float, String>, size: PDRectangle = PDRectangle(300f, 200f)): PDPage {
        val page = PDPage(size)
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs ->
            for ((y, text) in lines) {
                cs.beginText()
                cs.setFont(font, 12f)
                cs.newLineAtOffset(36f, y)
                cs.showText(text)
                cs.endText()
            }
        }
        return page
    }

    /** Where text starting at x = 36 reaches after [prefix]. */
    private fun textX(prefix: String) = 36f + font.getStringWidth(prefix) / 1000f * 12f

    private fun bytes(doc: PDDocument): ByteArray = ByteArrayOutputStream().also { doc.save(it) }.toByteArray()

    /** Runs Redact on [source] as the tool does and returns its report and the saved file. */
    private fun redactFile(
        source: ByteArray,
        boxes: Map<Int, List<PDRectangle>> = emptyMap(),
        phrase: String? = null,
        fill: RedactFill = RedactFill.BLACK,
        password: String? = null,
        keepProtection: Boolean = false
    ): Pair<RedactReport, ByteArray> {
        val input = TestPdfs.write(context.cacheDir, source, "redact-in.pdf")
        val output = File(context.cacheDir, "redact-out.pdf").apply { delete() }
        val report = runBlocking {
            redactPdf(context, input, Uri.fromFile(output), password, boxes, phrase, fill, keepProtection) { pdfboxPages(it) }
        }
        return report to output.readBytes()
    }

    /** Stands in for the platform renderer, which Robolectric lacks: pdfbox draws the page as displayed, at the size asked for. */
    private fun pdfboxPages(doc: PDDocument) = PageRasterizer { index, width, height ->
        val page = doc.getPage(index)
        val shownWidth = if (page.rotation % 180 != 0) page.cropBox.height else page.cropBox.width
        val rendered = PDFRenderer(doc).apply { setAnnotationsFilter { false } }.renderImage(index, width / shownWidth, ImageType.RGB)
        Bitmap.createScaledBitmap(rendered, width, height, true).copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun operators(page: PDPage): Set<String> =
        PDFStreamParser(page.contents.use { it.readBytes() }).run { parse(); tokens }.filterIsInstance<Operator>().map { it.name }.toSet()

    /** The one image a redacted page draws; fails if it draws anything else. */
    private fun pageImage(page: PDPage): PDImageXObject {
        val names = page.resources.xObjectNames.toList()
        assertEquals(1, names.size)
        assertTrue(page.resources.fontNames.none())
        return page.resources.getXObject(names.single()) as PDImageXObject
    }

    /** The colour of the redacted page image at user-space point ([x], [y]). */
    private fun pixelAt(page: PDPage, x: Float, y: Float): Int {
        val crop = page.cropBox
        val image = pageImage(page)
        // Where the point is on the unrotated page, then on the page as displayed, which the image shows.
        val u = (x - crop.lowerLeftX) / crop.width
        val v = (crop.upperRightY - y) / crop.height
        val (dx, dy) = when (page.rotation) {
            90 -> (1 - v) to u
            180 -> (1 - u) to (1 - v)
            270 -> v to (1 - u)
            else -> u to v
        }
        val px = (dx * image.width).toInt().coerceIn(0, image.width - 1)
        val py = (dy * image.height).toInt().coerceIn(0, image.height - 1)
        // The decoded samples, read directly: Robolectric's bitmap decoding of PDF images swaps red and blue.
        val samples = image.stream.createInputStream().use { it.readBytes() }
        val i = (py * image.width + px) * 3
        return Color.rgb(samples[i].toInt() and 0xFF, samples[i + 1].toInt() and 0xFF, samples[i + 2].toInt() and 0xFF)
    }

    private fun isBlack(colour: Int) = Color.red(colour) < 40 && Color.green(colour) < 40 && Color.blue(colour) < 40

    private fun isWhite(colour: Int) = Color.red(colour) > 215 && Color.green(colour) > 215 && Color.blue(colour) > 215

    @Test
    fun `a page with a box keeps only the burnt image, its thumbnail and text gone, and other pages are untouched`() {
        val source = PDDocument().use { doc ->
            textPage(doc, 150f to "Account 12345678", 50f to "Footer")
            textPage(doc, 150f to "Second page")
            doc.getPage(0).cosObject.setItem(COSName.getPDFName("Thumb"), doc.document.createCOSStream())
            bytes(doc)
        }
        val untouched = PDDocument.load(source).use { it.getPage(1).contents.use { s -> s.readBytes() } }
        val box = PDRectangle(30f, 140f, 200f, 20f)
        val (report, output) = redactFile(source, mapOf(0 to listOf(box)))
        assertEquals(listOf(0), report.pages)
        PDDocument.load(output).use { doc ->
            val page = doc.getPage(0)
            val ops = operators(page)
            assertTrue(ops.none { it in setOf("BT", "ET", "Tj", "TJ", "'", "\"", "Tf") })
            assertEquals(setOf("q", "cm", "Do", "Q"), ops)
            assertNull(page.cosObject.getDictionaryObject(COSName.getPDFName("Thumb")))
            assertTrue(isBlack(pixelAt(page, 100f, 150f)))
            assertFalse(isBlack(pixelAt(page, 280f, 190f)))
            assertTrue(doc.getPage(1).contents.use { it.readBytes() }.contentEquals(untouched))
            assertEquals(setOf("BT", "Tf", "Td", "Tj", "ET"), operators(doc.getPage(1)))
        }
        assertFalse(String(output, Charsets.ISO_8859_1).contains("12345678"))
    }

    @Test
    fun `a white box paints like paper`() {
        val source = PDDocument().use { doc ->
            val page = PDPage(PDRectangle(200f, 200f))
            doc.addPage(page)
            PDPageContentStream(doc, page).use {
                it.setNonStrokingColor(0f, 0f, 1f)
                it.addRect(0f, 0f, 200f, 200f)
                it.fill()
            }
            bytes(doc)
        }
        val (_, output) = redactFile(source, mapOf(0 to listOf(PDRectangle(50f, 50f, 50f, 50f))), fill = RedactFill.WHITE)
        PDDocument.load(output).use { doc ->
            assertTrue(isWhite(pixelAt(doc.getPage(0), 75f, 75f)))
            assertEquals(Color.BLUE, pixelAt(doc.getPage(0), 150f, 150f))
        }
    }

    @Test
    fun `drawn boxes and phrase matches land in place on a rotated page with an offset crop box`() {
        for (rotation in listOf(90, 180, 270)) {
            val source = PDDocument().use { doc ->
                val page = textPage(doc, 300f to "Rotated secret", size = PDRectangle(400f, 400f))
                page.cropBox = PDRectangle(20f, 250f, 300f, 100f)
                page.rotation = rotation
                bytes(doc)
            }
            val (report, output) = redactFile(source, mapOf(0 to listOf(PDRectangle(200f, 260f, 50f, 20f))), phrase = "secret")
            assertEquals(listOf(0), report.pages)
            PDDocument.load(output).use { doc ->
                val page = doc.getPage(0)
                assertEquals(rotation, page.rotation)
                assertEquals(PDRectangle(20f, 250f, 300f, 100f).toString(), page.cropBox.toString())
                val middle = (textX("Rotated ") + textX("Rotated secret")) / 2
                assertTrue("$rotation", isBlack(pixelAt(page, 225f, 270f)))
                assertTrue("$rotation", isWhite(pixelAt(page, 200f, 330f)))
                assertTrue("$rotation", isBlack(pixelAt(page, middle, 304f)))
                assertTrue("$rotation", isWhite(pixelAt(page, middle, 320f)))
                // The image shows the page as displayed; the page's own /Rotate is undone by its placement.
                val image = pageImage(page)
                val shown = if (rotation == 180) 300f / 100f else 100f / 300f
                assertEquals(shown, image.width.toFloat() / image.height, 0.02f)
            }
        }
    }

    @Test
    fun `a phrase is found whatever its case and burnt on every page that shows it, other pages left alone`() {
        val source = PDDocument().use { doc ->
            textPage(doc, 150f to "The word is CLASSIFIED here")
            textPage(doc, 150f to "Nothing to see")
            textPage(doc, 100f to "Also classified, lower case")
            bytes(doc)
        }
        val (report, output) = redactFile(source, phrase = " Classified ")
        assertEquals(listOf(0, 2), report.pages)
        PDDocument.load(output).use { doc ->
            val start = textX("The word is ")
            val end = textX("The word is CLASSIFIED")
            assertTrue(isBlack(pixelAt(doc.getPage(0), (start + end) / 2, 154f)))
            assertTrue(isWhite(pixelAt(doc.getPage(0), (start + end) / 2, 175f)))
            assertTrue(isBlack(pixelAt(doc.getPage(2), textX("Also cla"), 104f)))
            assertEquals(setOf("BT", "Tf", "Td", "Tj", "ET"), operators(doc.getPage(1)))
        }
        assertFalse(String(output, Charsets.ISO_8859_1).lowercase().contains("classified"))
    }

    @Test
    fun `a phrase under four characters is refused`() {
        PDDocument().use { doc ->
            textPage(doc, 100f to "abc")
            assertThrows(IllegalArgumentException::class.java) { redact(doc, emptyMap(), "abc", RedactFill.BLACK, pdfboxPages(doc)) }
            assertThrows(IllegalArgumentException::class.java) { redact(doc, emptyMap(), "  ab ", RedactFill.BLACK, pdfboxPages(doc)) }
            // Checked once folded, as searched: three em spaces become one space, leaving "a b".
            val em = 0x2003.toChar().toString()
            assertThrows(IllegalArgumentException::class.java) { redact(doc, emptyMap(), "A$em$em${em}B", RedactFill.BLACK, pdfboxPages(doc)) }
        }
    }

    @Test
    fun `a phrase that folds to nothing redacts nothing, and a ligature matches its letters`() {
        val ligature = 0xFB01.toChar()
        assertEquals("file one", foldPhrase(" ${ligature}le " + 0x2003.toChar() + " ONE "))
        val blank = listOf(0x00A0, 0x2003, 0x3000, 0x2007).map { it.toChar() }.joinToString("")
        assertEquals("", foldPhrase(blank))
        PDDocument().use { doc ->
            textPage(doc, 100f to "plain words")
            assertEquals(RedactReport(emptyList(), ScrubCounts()), redact(doc, emptyMap(), blank, RedactFill.BLACK, pdfboxPages(doc)))
        }
    }

    @Test
    fun `the user's default author never refills a field the scrub emptied`() {
        PreferencesManager.setDefaultAuthor(context, "Zebracorn Corp")
        val source = PDDocument().use { doc ->
            textPage(doc, 100f to "By Zebracorn Corp")
            doc.documentInformation.author = "Zebracorn Corp"
            bytes(doc)
        }
        val (report, output) = redactFile(source, phrase = "zebracorn corp")
        assertEquals(listOf(0), report.pages)
        assertNowhere(output, "Zebracorn")
    }

    @Test
    fun `a rewritten content stream loses the parameters of its old filter`() {
        val content = "/Span <</ActualText (Zebracorn)>> BDC EMC 0 0 1 rg 0 0 10 10 re f"
        val source = PDDocument().use { doc ->
            val page = PDPage(PDRectangle(100f, 100f))
            doc.addPage(page)
            // PNG "Up" prediction over one row: a filter-type byte of 0 (none) before the row's bytes.
            val predicted = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(byteArrayOf(0) + content.toByteArray()) } }
            val stream = doc.document.createCOSStream().apply {
                createRawOutputStream().use { it.write(predicted.toByteArray()) }
                setItem(COSName.FILTER, COSName.FLATE_DECODE)
                setItem(COSName.DECODE_PARMS, COSDictionary().apply {
                    setInt(COSName.PREDICTOR, 12)
                    setInt(COSName.COLUMNS, content.length)
                })
            }
            page.cosObject.setItem(COSName.CONTENTS, stream)
            assertEquals(content, String(stream.createInputStream().use { it.readBytes() }, Charsets.ISO_8859_1))
            bytes(doc)
        }
        val (report, output) = redactFile(source, phrase = "Zebracorn")
        assertEquals(1, report.scrubbed.contentText)
        PDDocument.load(output).use { doc ->
            val stream = doc.getPage(0).cosObject.getDictionaryObject(COSName.CONTENTS) as COSStream
            assertNull(stream.getDictionaryObject(COSName.DECODE_PARMS))
            assertEquals(content.replace("(Zebracorn)", "()"), String(stream.createInputStream().use { it.readBytes() }, Charsets.ISO_8859_1))
        }
    }

    @Test
    fun `a stream too large to read whole is never rewritten from part of it, and is reported`() {
        val content = "/Span <</ActualText (Zebracorn)>> BDC EMC " + "0 0 1 rg 0 0 10 10 re f ".repeat(10)
        val source = PDDocument().use { doc ->
            val page = PDPage(PDRectangle(100f, 100f))
            doc.addPage(page)
            page.setContents(PDStream(doc, content.byteInputStream()))
            bytes(doc)
        }
        val limit = streamReadLimit
        streamReadLimit = 64
        val (report, output) = try {
            redactFile(source, phrase = "Zebracorn")
        } finally {
            streamReadLimit = limit
        }
        assertEquals(1, report.scrubbed.skipped)
        PDDocument.load(output).use { doc ->
            assertEquals(content, String(doc.getPage(0).contents.use { it.readBytes() }, Charsets.ISO_8859_1))
        }
    }

    @Test
    fun `annotations and fields a box touches go, with their pop-ups, and the others stay`() {
        val source = PDDocument().use { doc ->
            val page = textPage(doc, 150f to "Signed by someone")
            val inside = PDAnnotationText().apply { rectangle = PDRectangle(40f, 140f, 10f, 10f); contents = "inside note" }
            val popup = PDAnnotationPopup().apply { rectangle = PDRectangle(200f, 0f, 50f, 50f); cosObject.setItem(COSName.PARENT, inside) }
            inside.cosObject.setItem(COSName.getPDFName("Popup"), popup)
            val outside = PDAnnotationText().apply { rectangle = PDRectangle(250f, 20f, 10f, 10f); contents = "outside note" }
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            val field = PDTextField(form).apply { partialName = "signer" }
            field.widgets.single().apply { rectangle = PDRectangle(100f, 145f, 80f, 15f); this.page = page }
            form.fields = listOf(field)
            page.annotations = listOf(inside, popup, outside, field.widgets.single())
            bytes(doc)
        }
        redactFile(source, mapOf(0 to listOf(PDRectangle(30f, 135f, 200f, 30f)))).second.let { output ->
            PDDocument.load(output).use { doc ->
                assertEquals(listOf("outside note"), doc.getPage(0).annotations.map { it.contents })
                assertTrue(doc.documentCatalog.acroForm.fields.isEmpty())
            }
            assertFalse(String(output, Charsets.ISO_8859_1).contains("inside note"))
        }
    }

    @Test
    fun `text under a drawn box is scrubbed elsewhere as whole words`() {
        val source = PDDocument().use { doc ->
            textPage(doc, 150f to "Name: Jonathan Example")
            doc.documentInformation.keywords = "jonathan example, Jonathan Examples, other"
            bytes(doc)
        }
        val box = PDRectangle(textX("Name: ") - 1f, 145f, textX("Name: Jonathan Example") - textX("Name: ") + 2f, 15f)
        val (report, output) = redactFile(source, mapOf(0 to listOf(box)))
        assertEquals(1, report.scrubbed.info)
        PDDocument.load(output).use { assertEquals(", Jonathan Examples, other", it.documentInformation.keywords) }
    }

    @Test
    fun `the source's protection is kept only when asked`() {
        val source = TestPdfs.userPassword(2)
        val box = mapOf(0 to listOf(PDRectangle(60f, 690f, 120f, 40f)))
        val (_, kept) = redactFile(source, box, password = TestPdfs.USER_PASSWORD, keepProtection = true)
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(kept).close() }
        PDDocument.load(kept, TestPdfs.USER_PASSWORD).use { assertEquals(setOf("q", "cm", "Do", "Q"), operators(it.getPage(0))) }
        val (_, open) = redactFile(source, box, password = TestPdfs.USER_PASSWORD)
        PDDocument.load(open).use { assertFalse(it.isEncrypted) }
    }

    @Test
    fun `a photo page is stored as JPEG and a text page losslessly`() {
        val source = PDDocument().use { doc ->
            val random = Random(7)
            val noise = Bitmap.createBitmap(IntArray(200 * 200) { random.nextInt() or 0xFF000000.toInt() }, 200, 200, Bitmap.Config.ARGB_8888)
            val photo = PDPage(PDRectangle(200f, 200f))
            doc.addPage(photo)
            PDPageContentStream(doc, photo).use { it.drawImage(LosslessFactory.createFromImage(doc, noise), 0f, 0f, 200f, 200f) }
            textPage(doc, 150f to "Plain text page")
            bytes(doc)
        }
        val box = listOf(PDRectangle(10f, 10f, 20f, 20f))
        val (_, output) = redactFile(source, mapOf(0 to box, 1 to box))
        PDDocument.load(output).use { doc ->
            assertEquals(listOf(COSName.DCT_DECODE.name), pageImage(doc.getPage(0)).stream.filters.map { it.name })
            assertEquals(listOf(COSName.FLATE_DECODE.name), pageImage(doc.getPage(1)).stream.filters.map { it.name })
        }
    }

    @Test
    fun `photo detection tells paper from pictures`() {
        val text = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        for (y in 100 until 120) for (x in 50 until 350) text.setPixel(x, y, Color.rgb(x % 256, y, 255 - x % 256))
        assertFalse(looksLikePhoto(text))
        val random = Random(3)
        val photo = Bitmap.createBitmap(IntArray(400 * 400) { random.nextInt() or 0xFF000000.toInt() }, 400, 400, Bitmap.Config.ARGB_8888)
        assertTrue(looksLikePhoto(photo))
        val flat = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        assertFalse(looksLikePhoto(flat))
    }

    @Test
    fun `PDF string tokens decode escapes, octal, hex and UTF-16`() {
        assertEquals("a(b)c\n", pdfStringText("""(a\(b\)c\n)"""))
        assertEquals("AB", pdfStringText("""(\101\102)"""))
        assertEquals("Zebra", pdfStringText("<5A 65 62 72 61>"))
        assertEquals("Zé", pdfStringText("<FEFF005A00E9>"))
        assertEquals("P", pdfStringText("<5>"))
    }

    private val secret = "Zebracorn"

    private fun embedded(doc: PDDocument, name: String, data: ByteArray) = PDComplexFileSpecification().apply {
        file = name
        embeddedFile = PDEmbeddedFile(doc, data.inputStream())
    }

    private fun zipOf(member: String, text: String): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(member))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }.toByteArray()

    /**
     * Page 1 shows [secret]; page 2 does not, but its marked content, metadata, notes, a free-text appearance and form fields mention it;
     * so do Info, XMP, a bookmark, embedded files (by name, text and zipped text), a file attachment and loose strings, one UTF-16.
     */
    private fun leakyFixture(): ByteArray = PDDocument().use { doc ->
        textPage(doc, 150f to "Top secret: $secret inside")
        val second = PDPage(PDRectangle(300f, 200f))
        doc.addPage(second)
        second.resources = PDResources().apply { put(COSName.getPDFName("F1"), font) }
        val marked = """
            /Span <</ActualText ($secret)>> BDC BT /F1 12 Tf 36 100 Td (Visible words) Tj ET EMC
            /Figure <</Alt <FEFF005A00650062007200610063006F0072006E>>> BDC EMC
            /Span <</ActualText (kept text)>> BDC EMC
        """.trimIndent()
        second.setContents(PDStream(doc, marked.byteInputStream()))
        second.metadata = PDMetadata(doc, "<x>$secret page</x>".byteInputStream())

        doc.documentInformation.title = "Report on $secret"
        doc.documentInformation.author = "Ann"
        doc.documentInformation.setCustomMetadataValue("Codename", "${secret.uppercase()}-7")
        doc.documentCatalog.metadata = PDMetadata(doc, "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><t>$secret</t></x:xmpmeta>".byteInputStream())

        val outline = PDDocumentOutline()
        outline.addLast(PDOutlineItem().apply { title = "Chapter $secret" })
        outline.addLast(PDOutlineItem().apply { title = "Intro" })
        doc.documentCatalog.documentOutline = outline

        val files = PDEmbeddedFilesNameTreeNode().apply {
            names = mapOf(
                "${secret.lowercase()}.txt" to embedded(doc, "a.txt", "harmless".toByteArray()),
                "notes.txt" to embedded(doc, "notes.txt", "the ${secret.uppercase()} plan".toByteArray()),
                "archive.zip" to embedded(doc, "archive.zip", zipOf("doc.xml", "<w:t>$secret</w:t>")),
                "keep.txt" to embedded(doc, "keep.txt", "nothing to see".toByteArray())
            )
        }
        doc.documentCatalog.names = PDDocumentNameDictionary(doc.documentCatalog).apply { embeddedFiles = files }

        val note = PDAnnotationText().apply { rectangle = PDRectangle(10f, 10f, 10f, 10f); contents = "${secret.lowercase()} note" }
        val fine = PDAnnotationText().apply { rectangle = PDRectangle(30f, 10f, 10f, 10f); contents = "fine" }
        val attachment = PDAnnotationFileAttachment().apply {
            rectangle = PDRectangle(50f, 10f, 10f, 10f)
            file = embedded(doc, "data.bin", "payload $secret".toByteArray())
        }
        val appearance = doc.document.createCOSStream().apply {
            setItem(COSName.TYPE, COSName.XOBJECT)
            setItem(COSName.SUBTYPE, COSName.FORM)
            setItem(COSName.BBOX, PDRectangle(0f, 0f, 100f, 20f).cosArray)
            createOutputStream(COSName.FLATE_DECODE).use { it.write("BT /Helv 12 Tf 2 5 Td ($secret) Tj ET".toByteArray()) }
        }
        val freeText = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, COSName.getPDFName("FreeText"))
            setItem(COSName.RECT, PDRectangle(100f, 10f, 100f, 20f).cosArray)
            setString(COSName.CONTENTS, "a label")
            setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, appearance) })
        }

        val form = PDAcroForm(doc)
        doc.documentCatalog.acroForm = form
        form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), font) }
        form.defaultAppearance = "/Helv 0 Tf 0 g"
        val text = PDTextField(form).apply { partialName = "agent" }
        text.widgets.single().apply { rectangle = PDRectangle(10f, 150f, 150f, 20f); page = second }
        val box = PDCheckBox(form).apply { partialName = "box"; alternateFieldName = "$secret box" }
        box.widgets.single().apply { rectangle = PDRectangle(200f, 150f, 15f, 15f); page = second }
        form.fields = listOf(text, box)
        second.annotations = listOf(note, fine, attachment, text.widgets.single(), box.widgets.single())
        (second.cosObject.getDictionaryObject(COSName.ANNOTS) as COSArray).add(freeText)
        text.value = "Agent $secret"

        // XFA form data, a script stream, a form no page draws, private data and a name value.
        form.cosObject.setItem(COSName.getPDFName("XFA"), textStream(doc, "<xdp:xdp><field>$secret</field></xdp:xdp>"))
        doc.documentCatalog.cosObject.setItem(COSName.OPEN_ACTION, COSDictionary().apply {
            setItem(COSName.S, COSName.getPDFName("JavaScript"))
            setItem(COSName.JS, textStream(doc, "app.alert('$secret');"))
        })
        val undrawn = textStream(doc, "BT /F1 12 Tf 10 10 Td ($secret) Tj ET").apply {
            setItem(COSName.TYPE, COSName.XOBJECT)
            setItem(COSName.SUBTYPE, COSName.FORM)
            setItem(COSName.BBOX, PDRectangle(0f, 0f, 100f, 20f).cosArray)
        }
        second.resources.cosObject.setItem(COSName.XOBJECT, COSDictionary().apply { setItem(COSName.getPDFName("Fm9"), undrawn) })
        doc.documentCatalog.cosObject.setItem(COSName.getPDFName("ZData"), textStream(doc, "private ${secret.lowercase()} data"))
        doc.documentCatalog.cosObject.setItem(COSName.getPDFName("ZName"), COSName.getPDFName("$secret-label"))
        doc.documentCatalog.cosObject.setItem(
            COSName.getPDFName("ZCustom"),
            COSArray().apply {
                add(COSString("about ${secret.lowercase()}"))
                add(COSString("Зебра $secret"))
            }
        )
        bytes(doc)
    }

    private fun textStream(doc: PDDocument, text: String): COSStream =
        doc.document.createCOSStream().apply { createOutputStream(COSName.FLATE_DECODE).use { it.write(text.toByteArray()) } }

    /** Fails if [phrase] shows anywhere in [file]: its raw bytes, any string, or any decoded stream, attachment or string token in it. */
    private fun assertNowhere(file: ByteArray, phrase: String) {
        val needle = phrase.lowercase()
        assertFalse("raw bytes", String(file, Charsets.ISO_8859_1).lowercase().contains(needle))
        for (offset in 0..1) assertFalse("raw UTF-16", String(file, offset, file.size - offset, Charsets.UTF_16BE).lowercase().contains(needle))
        PDDocument.load(file).use { doc ->
            val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
            val pending = ArrayDeque<COSBase>().apply { add(doc.document.trailer) }
            while (pending.isNotEmpty()) {
                val value = pending.removeLast().let { if (it is COSObject) it.`object` else it } ?: continue
                if (!seen.add(value)) continue
                when (value) {
                    is COSString -> assertFalse("string ${value.string}", value.string.lowercase().contains(needle))
                    is COSDictionary -> {
                        if (value is COSStream) {
                            val data = value.createInputStream().use { it.readBytes() }
                            val latin = String(data, Charsets.ISO_8859_1)
                            val texts = attachmentTexts(data) + latin + Regex("""<[0-9A-Fa-f\s]*>""").findAll(latin).map { pdfStringText(it.value) }
                            texts.forEach { assertFalse("stream text $it", it.lowercase().contains(needle)) }
                        }
                        value.values.forEach(pending::add)
                    }
                    is COSArray -> value.forEach { it?.let(pending::add) }
                    is COSName -> assertFalse("name ${value.name}", value.name.lowercase().contains(needle))
                    else -> {}
                }
            }
        }
    }

    @Test
    fun `the fixture leaks the phrase everywhere before redaction`() {
        assertThrows(AssertionError::class.java) { assertNowhere(leakyFixture(), secret) }
    }

    @Test
    fun `a redacted phrase is nowhere in the saved file, and what does not mention it stays`() {
        val (report, output) = redactFile(leakyFixture(), phrase = secret.lowercase())
        assertEquals(listOf(0), report.pages)
        assertNowhere(output, secret)
        val expected = ScrubCounts(
            info = 2, xmp = 2, outline = 1, annotations = 2, fields = 2, attachments = 4, contentText = 3, streams = 3, strings = 2, names = 1
        )
        assertEquals(expected, report.scrubbed)
        PDDocument.load(output).use { doc ->
            assertEquals("Report on ", doc.documentInformation.title)
            assertEquals("Ann", doc.documentInformation.author)
            assertEquals("-7", doc.documentInformation.getCustomMetadataValue("Codename"))
            assertNull(doc.documentCatalog.metadata)
            assertNull(doc.getPage(1).metadata)
            assertEquals(listOf("Chapter ", "Intro"), doc.documentCatalog.documentOutline.children().map { it.title })
            assertEquals(setOf("keep.txt"), doc.documentCatalog.names.embeddedFiles.names.keys)
            assertEquals(listOf("fine"), doc.getPage(1).annotations.filter { it.subtype == "Text" }.map { it.contents })
            assertTrue(doc.getPage(1).annotations.none { it.subtype == "FreeText" || it.subtype == "FileAttachment" })
            val form = doc.documentCatalog.acroForm
            assertEquals(listOf("agent"), form.fields.map { it.partialName })
            assertEquals("Agent ", (form.getField("agent") as PDTextField).value)
            val content = String(doc.getPage(1).contents.use { it.readBytes() }, Charsets.ISO_8859_1)
            assertTrue(content.contains("(Visible words) Tj"))
            assertTrue(content.contains("/ActualText (kept text)"))
            assertNotNull(doc.documentCatalog.cosObject.getDictionaryObject(COSName.getPDFName("ZCustom")))
        }
    }
}
