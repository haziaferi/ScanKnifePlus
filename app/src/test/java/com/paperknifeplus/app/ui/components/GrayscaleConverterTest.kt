package com.paperknifeplus.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.common.function.PDFunction
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.InflaterInputStream
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GrayscaleConverterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private val red = 0.213f
    private val green = 0.715f
    private val blue = 0.072f

    /** A rasterizer that must not be used. */
    private val noFallback = PageRasterizer { i, _, _ -> error("Page $i fell back") }

    private fun name(text: String) = COSName.getPDFName(text)

    private fun array(vararg items: Any) = COSArray().apply {
        items.forEach {
            add(
                when (it) {
                    is COSBase -> it
                    is Int -> COSInteger.get(it.toLong())
                    is Float -> COSFloat(it)
                    is String -> name(it)
                    else -> error("$it")
                }
            )
        }
    }

    private fun stream(doc: PDDocument, content: ByteArray, vararg entries: Pair<String, COSBase>): COSStream =
        doc.document.createCOSStream().apply {
            createOutputStream().use { it.write(content) }
            entries.forEach { (key, value) -> setItem(name(key), value) }
        }

    private fun form(doc: PDDocument, content: String, resources: COSDictionary? = null): COSStream =
        stream(doc, content.toByteArray(), "Type" to name("XObject"), "Subtype" to name("Form"), "BBox" to array(0, 0, 100, 100)).apply {
            if (resources != null) setItem(COSName.RESOURCES, resources)
        }

    private fun resources(vararg kinds: Pair<String, Map<String, COSBase>>) = COSDictionary().apply {
        kinds.forEach { (kind, entries) -> setItem(name(kind), COSDictionary().apply { entries.forEach { (k, v) -> setItem(name(k), v) } }) }
    }

    private fun addPage(doc: PDDocument, content: ByteArray, resources: COSDictionary = COSDictionary()): PDPage =
        PDPage(PDRectangle.LETTER).also { page ->
            page.resources = PDResources(resources)
            page.setContents(PDStream(doc, content.inputStream()))
            doc.addPage(page)
        }

    /** Converts [doc], saves and reloads it, so every check is on what a reader would get. */
    private fun convertAndReload(doc: PDDocument, rasterizer: PageRasterizer = noFallback): Pair<PDDocument, Int> {
        val fallbacks = GrayscaleConverter(doc, rasterizer).convert()
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return PDDocument.load(out.toByteArray()) to fallbacks
    }

    private fun tokens(bytes: ByteArray): List<Any> = PDFStreamParser(bytes).run {
        parse()
        tokens
    }

    private fun text(tokens: List<Any>): String = ByteArrayOutputStream().also { ContentStreamWriter(it).writeTokens(tokens) }.toString("ISO-8859-1")
        .replace(Regex("\\s+"), " ").trim()

    private fun pageText(page: PDPage) = text(tokens(page.contents.use { it.readBytes() }))

    private fun streamText(stream: COSStream) = text(tokens(stream.createInputStream().use { it.readBytes() }))

    private fun operators(text: String): Set<String> = tokens(text.toByteArray()).filterIsInstance<Operator>().map { it.name }.toSet()

    private fun pixels(image: COSStream) = PDStream(image).createInputStream().use { it.readBytes() }.map { it.toInt() and 0xFF }

    private fun xobject(resources: PDResources, key: String) = resources.cosObject.let {
        (it.getDictionaryObject(COSName.XOBJECT) as COSDictionary).getDictionaryObject(name(key)) as COSStream
    }

    private fun assertGray(expected: Float, actual: COSBase?) = assertEquals(expected, (actual as COSNumber).floatValue(), 0.0002f)

    @Test
    fun `colour operators become gray ones and text stays`() {
        val doc = PDDocument()
        val icc = stream(doc, ByteArray(0), "N" to COSInteger.get(3))
        val cyanTint = COSDictionary().apply {
            setInt(COSName.FUNCTION_TYPE, 2)
            setItem(COSName.DOMAIN, array(0, 1))
            setItem(COSName.C0, array(0, 0, 0, 0))
            setItem(COSName.C1, array(1, 0, 0, 0))
            setInt(COSName.N, 1)
        }
        val res = resources("ColorSpace" to mapOf("CS0" to array("ICCBased", icc), "CS1" to array("Separation", "Spot", "DeviceCMYK", cyanTint)))
        val page = addPage(
            doc,
            """
            1 0 0 rg 0 0 1 RG 0 1 1 0 k 0 0 0 1 K
            /CS0 cs 0 1 0 sc
            /CS1 CS 1 SCN
            /CS0 cs q 0.5 g Q 1 0 0 scn
            BT /F1 12 Tf 72 700 Td (Hello) Tj ET
            """.trimIndent().toByteArray(),
            res
        )
        page.resources.put(name("F1"), PDType1Font.HELVETICA)

        val (out, fallbacks) = convertAndReload(doc)
        out.use {
            assertEquals(0, fallbacks)
            val text = pageText(it.getPage(0))
            assertEquals(
                "0.213 g 0.072 G 0.213 g 0 G 0 g 0.715 g 0.787 G 0.787 G 0 g q 0.5 g Q 0.213 g BT /F1 12 Tf 72 700 Td (Hello) Tj ET",
                text
            )
            assertTrue(operators(text).none { op -> op in setOf("rg", "RG", "k", "K", "cs", "CS", "sc", "SC", "scn", "SCN") })
        }
    }

    @Test
    fun `nested forms, patterns and Type 3 glyphs are converted`() {
        val doc = PDDocument()
        val inner = form(doc, "0 1 0 rg 0 0 10 10 re f")
        val outer = form(doc, "/Fm1 Do 0 0 1 RG", resources("XObject" to mapOf("Fm1" to inner)))
        val uncoloured = stream(doc, "0 0 5 5 re f".toByteArray(), "PatternType" to COSInteger.ONE, "PaintType" to COSInteger.TWO,
            "TilingType" to COSInteger.ONE, "BBox" to array(0, 0, 5, 5), "XStep" to COSInteger.get(5), "YStep" to COSInteger.get(5), "Resources" to COSDictionary())
        val coloured = stream(doc, "1 0 0 rg 0 0 5 5 re f".toByteArray(), "PatternType" to COSInteger.ONE, "PaintType" to COSInteger.ONE,
            "TilingType" to COSInteger.ONE, "BBox" to array(0, 0, 5, 5), "XStep" to COSInteger.get(5), "YStep" to COSInteger.get(5), "Resources" to COSDictionary())
        val glyph = stream(doc, "0 0 0 0 750 750 d1 0 0 1 rg 0 0 750 750 re f".toByteArray())
        val type3 = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.TYPE3)
            setItem(COSName.FONT_BBOX, array(0, 0, 750, 750))
            setItem(COSName.FONT_MATRIX, array(0.001f, 0, 0, 0.001f, 0, 0))
            setItem(COSName.CHAR_PROCS, COSDictionary().apply { setItem(name("a"), glyph) })
            setItem(COSName.ENCODING, COSDictionary().apply { setItem(COSName.DIFFERENCES, array(97, "a")) })
            setInt(COSName.FIRST_CHAR, 97)
            setInt(COSName.LAST_CHAR, 97)
            setItem(COSName.WIDTHS, array(750))
        }
        val res = resources(
            "XObject" to mapOf("Fm0" to outer),
            "ColorSpace" to mapOf("P" to array("Pattern", "DeviceRGB")),
            "Pattern" to mapOf("P0" to uncoloured, "P1" to coloured),
            "Font" to mapOf("T3" to type3)
        )
        addPage(doc, "/Fm0 Do /P cs 1 0 0 /P0 scn 0 0 50 50 re f /Pattern cs /P1 scn 0 0 9 9 re f BT /T3 12 Tf (a) Tj ET".toByteArray(), res)

        val (out, _) = convertAndReload(doc)
        out.use {
            val page = it.getPage(0)
            assertEquals("/Fm0 Do /GrayPattern0 cs 0.213 /P0 scn 0 0 50 50 re f /Pattern cs /P1 scn 0 0 9 9 re f BT /T3 12 Tf (a) Tj ET", pageText(page))
            val spaces = page.resources.cosObject.getDictionaryObject(COSName.COLORSPACE) as COSDictionary
            assertEquals(array("Pattern", "DeviceGray").toList(), (spaces.getDictionaryObject(name("GrayPattern0")) as COSArray).toList())

            val fm0 = xobject(page.resources, "Fm0")
            assertEquals("/Fm1 Do 0.072 G", streamText(fm0))
            val fm1 = ((fm0.getDictionaryObject(COSName.RESOURCES) as COSDictionary).getDictionaryObject(COSName.XOBJECT) as COSDictionary)
                .getDictionaryObject(name("Fm1")) as COSStream
            assertEquals("0.715 g 0 0 10 10 re f", streamText(fm1))

            val patterns = page.resources.cosObject.getDictionaryObject(COSName.PATTERN) as COSDictionary
            assertEquals("0.213 g 0 0 5 5 re f", streamText(patterns.getDictionaryObject(name("P1")) as COSStream))
            val font = (page.resources.cosObject.getDictionaryObject(COSName.FONT) as COSDictionary).getDictionaryObject(name("T3")) as COSDictionary
            val proc = (font.getDictionaryObject(COSName.CHAR_PROCS) as COSDictionary).getDictionaryObject(name("a")) as COSStream
            assertEquals("0 0 0 0 750 750 d1 0.072 g 0 0 750 750 re f", streamText(proc))
        }
    }

    @Test
    fun `annotations, form defaults, links, outline and metadata stay, in gray`() {
        val doc = PDDocument()
        val page = addPage(doc, "0 0 1 rg 0 0 10 10 re f".toByteArray())
        val appearance = form(doc, "1 0 0 RG 0 0 1 rg 0 0 10 10 re B")
        val square = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, name("Square"))
            setItem(COSName.RECT, array(10, 10, 60, 60))
            setItem(COSName.C, array(1, 0, 0))
            setItem(COSName.IC, array(0, 0, 1))
            setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, appearance) })
        }
        val widget = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, COSName.WIDGET)
            setItem(COSName.FT, name("Tx"))
            setItem(COSName.T, COSString("name"))
            setItem(COSName.RECT, array(100, 100, 200, 120))
            setItem(COSName.DA, COSString("/Helv 12 Tf 1 0 0 rg"))
            setItem(COSName.MK, COSDictionary().apply { setItem(COSName.BG, array(0, 0, 1)) })
        }
        val link = PDAnnotationLink().apply {
            rectangle = PDRectangle(10f, 100f, 50f, 20f)
            action = PDActionURI().apply { uri = "https://example.org" }
            cosObject.setItem(COSName.C, array(0, 0, 1))
        }
        page.annotations = listOf(link)
        page.cosObject.getCOSArray(COSName.ANNOTS).apply { add(square); add(widget) }
        doc.documentCatalog.cosObject.setItem(COSName.ACRO_FORM, COSDictionary().apply {
            setItem(COSName.FIELDS, array(widget))
            setItem(COSName.DA, COSString("/Helv 0 Tf 0 1 0 rg"))
        })
        val outline = PDDocumentOutline()
        outline.addLast(PDOutlineItem().apply {
            title = "Chapter"
            cosObject.setItem(COSName.C, array(1, 0, 0))
        })
        doc.documentCatalog.documentOutline = outline
        doc.documentInformation.title = "Kept title"

        val (out, _) = convertAndReload(doc)
        out.use {
            assertEquals("Kept title", it.documentInformation.title)
            val annotations = it.getPage(0).annotations
            assertEquals(3, annotations.size)
            val keptLink = annotations[0] as PDAnnotationLink
            assertEquals("https://example.org", (keptLink.action as PDActionURI).uri)
            assertGray(blue, keptLink.cosObject.getCOSArray(COSName.C).get(0))

            val keptSquare = annotations[1].cosObject
            assertEquals(1, keptSquare.getCOSArray(COSName.C).size())
            assertGray(red, keptSquare.getCOSArray(COSName.C).get(0))
            assertGray(blue, keptSquare.getCOSArray(COSName.IC).get(0))
            val ap = (keptSquare.getDictionaryObject(COSName.AP) as COSDictionary).getDictionaryObject(COSName.N) as COSStream
            assertEquals("0.213 G 0.072 g 0 0 10 10 re B", streamText(ap))

            val keptWidget = annotations[2].cosObject
            assertEquals("/Helv 12 Tf 0.213 g", keptWidget.getString(COSName.DA))
            assertGray(blue, (keptWidget.getDictionaryObject(COSName.MK) as COSDictionary).getCOSArray(COSName.BG).get(0))
            val form = it.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as COSDictionary
            assertEquals("/Helv 0 Tf 0.715 g", form.getString(COSName.DA))

            val item = it.documentCatalog.documentOutline.firstChild
            assertEquals("Chapter", item.title)
            val colour = item.cosObject.getCOSArray(COSName.C)
            assertEquals(3, colour.size())
            (0 until 3).forEach { i -> assertGray(red, colour.get(i)) }
        }
    }

    @Test
    fun `images become one-channel gray, JPEGs as JPEG and the rest losslessly`() {
        val doc = PDDocument()
        val twoPixels = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply {
            setPixel(0, 0, Color.RED)
            setPixel(1, 0, Color.BLUE)
        }
        val lossless = LosslessFactory.createFromImage(doc, twoPixels)
        val photo = JPEGFactory.createFromImage(doc, Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }, 0.9f)
        val palette = stream(doc, byteArrayOf(0b01000000), "Type" to name("XObject"), "Subtype" to name("Image"), "Width" to COSInteger.TWO,
            "Height" to COSInteger.ONE, "BitsPerComponent" to COSInteger.ONE,
            "ColorSpace" to array("Indexed", "DeviceRGB", 1, COSString(byteArrayOf(-1, 0, 0, 0, 0, -1))))
        val res = resources("XObject" to mapOf("Im0" to lossless.cosObject, "Im1" to photo.cosObject, "Im2" to palette))
        val inline = "BI /W 2 /H 1 /BPC 8 /CS /RGB ID ".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(-1, 0, 0, 0, 0, -1) + "\nEI".toByteArray()
        addPage(doc, "/Im0 Do /Im1 Do /Im2 Do ".toByteArray() + inline, res)

        val (out, _) = convertAndReload(doc)
        out.use {
            val page = it.getPage(0)
            for (key in listOf("Im0", "Im1", "Im2")) {
                val image = xobject(page.resources, key)
                assertEquals(key, COSName.DEVICEGRAY, image.getDictionaryObject(COSName.COLORSPACE))
                assertEquals(key, 8, image.getInt(COSName.BITS_PER_COMPONENT))
            }
            val im0 = xobject(page.resources, "Im0")
            assertEquals(COSName.FLATE_DECODE, im0.getDictionaryObject(COSName.FILTER))
            assertEquals(listOf(54, 18), pixels(im0))
            assertEquals(listOf(54, 18), pixels(xobject(page.resources, "Im2")))

            val im1 = xobject(page.resources, "Im1")
            assertEquals(COSName.DCT_DECODE, im1.getDictionaryObject(COSName.FILTER))
            val jpeg = im1.createRawInputStream().use { s -> s.readBytes() }
            assertEquals(1, JpegHeadersTest.header(jpeg)!!.components)
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            assertTrue(abs(Color.red(decoded.getPixel(8, 8)) - 54) <= 4)

            val bi = tokens(page.contents.use { s -> s.readBytes() }).filterIsInstance<Operator>().single { op -> op.name == "BI" }
            assertEquals(COSName.G, bi.imageParameters.getDictionaryObject(COSName.CS))
            assertEquals(8, bi.imageParameters.getInt(COSName.BPC))
            assertArrayEquals(byteArrayOf(54, 18), InflaterInputStream(bi.imageData.inputStream()).readBytes())
        }
    }

    @Test
    fun `a shading gets a sampled gray function`() {
        val doc = PDDocument()
        val shading = COSDictionary().apply {
            setInt(COSName.SHADING_TYPE, 2)
            setItem(COSName.COLORSPACE, COSName.DEVICERGB)
            setItem(COSName.COORDS, array(0, 0, 100, 0))
            setItem(COSName.FUNCTION, COSDictionary().apply {
                setInt(COSName.FUNCTION_TYPE, 2)
                setItem(COSName.DOMAIN, array(0, 1))
                setItem(COSName.C0, array(1, 0, 0))
                setItem(COSName.C1, array(0, 0, 1))
                setInt(COSName.N, 1)
            })
        }
        addPage(doc, "/Sh0 sh".toByteArray(), resources("Shading" to mapOf("Sh0" to shading)))

        val (out, _) = convertAndReload(doc)
        out.use {
            val kept = (it.getPage(0).resources.cosObject.getDictionaryObject(COSName.SHADING) as COSDictionary).getDictionaryObject(name("Sh0")) as COSDictionary
            assertEquals(COSName.DEVICEGRAY, kept.getDictionaryObject(COSName.COLORSPACE))
            val function = PDFunction.create(kept.getDictionaryObject(COSName.FUNCTION))
            assertEquals(0, function.functionType)
            assertEquals(red, function.eval(floatArrayOf(0f))[0], 0.002f)
            assertEquals((red + blue) / 2, function.eval(floatArrayOf(0.5f))[0], 0.002f)
            assertEquals(blue, function.eval(floatArrayOf(1f))[0], 0.002f)
        }
    }

    @Test
    fun `a page with an unknown colour space alone falls back to a gray image of itself`() {
        val doc = PDDocument()
        addPage(doc, "1 0 0 rg 0 0 10 10 re f".toByteArray())
        val unknown = resources("ColorSpace" to mapOf("X" to array("Bogus")))
        addPage(doc, "/X cs 1 sc 0 0 10 10 re f".toByteArray(), unknown)
        addPage(doc, "/X cs 1 sc".toByteArray(), unknown).rotation = 90
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        val rasterizer = PageRasterizer { i, w, h ->
            calls += Triple(i, w, h)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        }

        val (out, fallbacks) = convertAndReload(doc, rasterizer)
        out.use {
            assertEquals(2, fallbacks)
            assertEquals(listOf(Triple(1, 1275, 1650), Triple(2, 1650, 1275)), calls)
            assertEquals("0.213 g 0 0 10 10 re f", pageText(it.getPage(0)))

            val page = it.getPage(1)
            val names = page.resources.xObjectNames.toList()
            assertEquals(1, names.size)
            val image = xobject(page.resources, names[0].name)
            assertEquals(COSName.DEVICEGRAY, image.getDictionaryObject(COSName.COLORSPACE))
            assertEquals(COSName.FLATE_DECODE, image.getDictionaryObject(COSName.FILTER))
            assertEquals(1275, image.getInt(COSName.WIDTH))
            assertTrue(pixels(image).all { p -> p == 54 })
            assertTrue(page.resources.cosObject.getDictionaryObject(COSName.COLORSPACE) == null)

            // The rotated page's image is turned back so /Rotate 90 shows it upright on the 612 x 792 crop box.
            val cm = tokens(it.getPage(2).contents.use { s -> s.readBytes() })
            val index = cm.indexOfFirst { t -> t is Operator && t.name == "cm" }
            assertEquals(listOf(0f, 792f, -612f, 0f, 612f, 0f), cm.subList(index - 6, index).map { t -> (t as COSNumber).floatValue() })
        }
    }

    /** Separation whose tint transform gives that much cyan, in DeviceCMYK. */
    private fun cyanSeparation() = array("Separation", "Spot", "DeviceCMYK", COSDictionary().apply {
        setInt(COSName.FUNCTION_TYPE, 2)
        setItem(COSName.DOMAIN, array(0, 1))
        setItem(COSName.C0, array(0, 0, 0, 0))
        setItem(COSName.C1, array(1, 0, 0, 0))
        setInt(COSName.N, 1)
    })

    @Test
    fun `forms and Type 3 glyphs are converted with the colour space their caller set`() {
        val doc = PDDocument()
        val form = form(doc, "1 sc 0 0 10 10 re f")
        val glyph = stream(doc, "750 0 d0 1 sc 0 0 750 750 re f".toByteArray())
        val type3 = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.TYPE3)
            setItem(COSName.FONT_BBOX, array(0, 0, 750, 750))
            setItem(COSName.FONT_MATRIX, array(0.001f, 0, 0, 0.001f, 0, 0))
            setItem(COSName.CHAR_PROCS, COSDictionary().apply { setItem(name("a"), glyph) })
            setItem(COSName.ENCODING, COSDictionary().apply { setItem(COSName.DIFFERENCES, array(97, "a")) })
            setInt(COSName.FIRST_CHAR, 97)
            setInt(COSName.LAST_CHAR, 97)
            setItem(COSName.WIDTHS, array(750))
        }
        val palette = array("Indexed", "DeviceRGB", 1, COSString(byteArrayOf(-1, 0, 0, 0, 0, -1)))
        val res = resources(
            "ColorSpace" to mapOf("CS1" to cyanSeparation(), "CS2" to palette),
            "XObject" to mapOf("Fm0" to form),
            "Font" to mapOf("T3" to type3)
        )
        addPage(doc, "/CS1 cs /Fm0 Do BT /T3 12 Tf (a) Tj ET /CS2 cs /Fm0 Do".toByteArray(), res)

        val (out, fallbacks) = convertAndReload(doc)
        out.use {
            assertEquals(0, fallbacks)
            val page = it.getPage(0)
            assertEquals("0.787 g /Fm0 Do BT /T3 12 Tf (a) Tj ET 0.213 g /GrayForm0 Do", pageText(page))
            assertEquals("0.787 g 0 0 10 10 re f", streamText(xobject(page.resources, "Fm0")))
            assertEquals("0.072 g 0 0 10 10 re f", streamText(xobject(page.resources, "GrayForm0")))
            val font = (page.resources.cosObject.getDictionaryObject(COSName.FONT) as COSDictionary).getDictionaryObject(name("T3")) as COSDictionary
            val proc = (font.getDictionaryObject(COSName.CHAR_PROCS) as COSDictionary).getDictionaryObject(name("a")) as COSStream
            assertEquals("750 0 d0 0.787 g 0 0 750 750 re f", streamText(proc))
        }
    }

    @Test
    fun `a CMYK JPEG becomes a gray JPEG`() {
        val doc = PDDocument()
        // A 16x16 Adobe CMYK JPEG, all C=0 M=100% Y=100% K=0 (red), from an outside encoder: the platform cannot write CMYK.
        val cmykJpeg = java.util.Base64.getDecoder().decode(
            "/9j/7gAOQWRvYmUAZAAAAAAA/9sAQwACAQEBAQECAQEBAgICAgIEAwICAgIFBAQDBAYFBgYGBQYGBgcJCAYHCQcGBggLCAkKCgoKCgYICwwLCgwJCgoK/8AAFAgAEAAQBEMRAE0R" +
                "AFkRAEsRAP/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJ" +
                "ChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV" +
                "1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/aAA4EQwBNAFkASwAAPwD9/K/n/r+f+v38oooooooooooor//Z"
        )
        val image = stream(doc, cmykJpeg, "Type" to name("XObject"), "Subtype" to name("Image"), "Width" to COSInteger.get(16),
            "Height" to COSInteger.get(16), "BitsPerComponent" to COSInteger.get(8), "ColorSpace" to name("DeviceCMYK"),
            "Filter" to name("DCTDecode"))
        addPage(doc, "/Im0 Do".toByteArray(), resources("XObject" to mapOf("Im0" to image)))

        val (out, fallbacks) = convertAndReload(doc)
        out.use {
            assertEquals(0, fallbacks)
            val converted = xobject(it.getPage(0).resources, "Im0")
            assertEquals(COSName.DEVICEGRAY, converted.getDictionaryObject(COSName.COLORSPACE))
            val jpeg = converted.createRawInputStream().use { s -> s.readBytes() }
            assertEquals(1, JpegHeadersTest.header(jpeg)!!.components)
            val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            assertTrue(abs(Color.red(decoded.getPixel(8, 8)) - 54) <= 6)
        }
    }

    @Test
    fun `an annotation that cannot be converted sends its page to the fallback and is drawn into the image`() {
        val doc = PDDocument()
        val page = addPage(doc, "1 0 0 rg 0 0 10 10 re f".toByteArray())
        val appearance = form(doc, "/X cs 1 sc 0 0 10 10 re f", resources("ColorSpace" to mapOf("X" to array("Bogus"))))
        val stamp = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, name("Stamp"))
            setItem(COSName.RECT, array(10, 10, 60, 60))
            setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, appearance) })
        }
        val note = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, name("FreeText"))
            setItem(COSName.RECT, array(100, 100, 300, 140))
            setItem(COSName.DS, COSString("font: 12pt Helvetica; color:#FF0000"))
            setItem(COSName.RC, COSString("<body style=\"color:rgb(0,0,255)\"><p style=\"color:#f00\">x</p></body>"))
        }
        page.cosObject.setItem(COSName.ANNOTS, array(stamp, note))
        val rasterizer = PageRasterizer { _, w, h -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) } }

        val (out, fallbacks) = convertAndReload(doc, rasterizer)
        out.use {
            assertEquals(1, fallbacks)
            val kept = it.getPage(0)
            assertEquals(listOf("FreeText"), kept.annotations.map { a -> a.subtype })
            val freeText = kept.annotations.single().cosObject
            assertEquals("font: 12pt Helvetica; color:#363636", freeText.getString(COSName.DS))
            assertEquals("<body style=\"color:#121212\"><p style=\"color:#363636\">x</p></body>", freeText.getString(COSName.RC))
            val image = xobject(kept.resources, kept.resources.xObjectNames.single().name)
            // Away from the stamp, the image is the rendered page, untouched by drawing the stamp over it.
            assertEquals(54, pixels(image)[1275 * 100 + 1000])
        }
    }

    @Test
    fun `a widget drawn into the fallback image leaves its field, and a field left without widgets leaves the form`() {
        val doc = PDDocument()
        val page = addPage(doc, "0 0 10 10 re f".toByteArray())
        val unconvertible = { form(doc, "/X cs 1 sc 0 0 10 10 re f", resources("ColorSpace" to mapOf("X" to array("Bogus")))) }
        val convertible = form(doc, "0 0 1 rg 0 0 10 10 re f")
        fun widget(parent: COSDictionary, appearance: COSStream, y: Int) = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setItem(COSName.SUBTYPE, COSName.WIDGET)
            setItem(COSName.RECT, array(10, y, 60, y + 20))
            setItem(COSName.PARENT, parent)
            setItem(COSName.AP, COSDictionary().apply { setItem(COSName.N, appearance) })
        }
        val single = COSDictionary().apply { setItem(COSName.FT, name("Tx")); setItem(COSName.T, COSString("single")) }
        val pair = COSDictionary().apply { setItem(COSName.FT, name("Btn")); setItem(COSName.T, COSString("pair")) }
        val onlyWidget = widget(single, unconvertible(), 10)
        val failing = widget(pair, unconvertible(), 40)
        val kept = widget(pair, convertible, 70)
        single.setItem(COSName.KIDS, array(onlyWidget))
        pair.setItem(COSName.KIDS, array(failing, kept))
        page.cosObject.setItem(COSName.ANNOTS, array(onlyWidget, failing, kept))
        doc.documentCatalog.cosObject.setItem(COSName.ACRO_FORM, COSDictionary().apply { setItem(COSName.FIELDS, array(single, pair)) })
        val rasterizer = PageRasterizer { _, w, h -> Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) } }

        val (out, fallbacks) = convertAndReload(doc, rasterizer)
        out.use {
            assertEquals(1, fallbacks)
            val fields = (it.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as COSDictionary).getCOSArray(COSName.FIELDS)
            assertEquals(1, fields.size())
            val keptField = fields.getObject(0) as COSDictionary
            assertEquals("pair", keptField.getString(COSName.T))
            val kids = keptField.getCOSArray(COSName.KIDS)
            assertEquals(1, kids.size())
            val annotations = it.getPage(0).annotations
            assertEquals(1, annotations.size)
            assertTrue(kids.getObject(0) === annotations.single().cosObject)
        }
    }

    @Test
    fun `the tool removes encryption, keeps the page and reports no image pages`() {
        val source = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(pages = 2), "locked.pdf")
        val output = File(context.cacheDir, "gray.pdf")
        val imagePages = runBlocking { performGrayscaleRewrite(context, source, Uri.fromFile(output), TestPdfs.USER_PASSWORD) { _, _ -> } }
        assertEquals(0, imagePages)
        PDDocument.load(output).use {
            assertFalse(it.isEncrypted)
            assertEquals(2, it.numberOfPages)
        }
        assertEquals("Colors removed; 2 pages could only be converted as images", grayscaleSummary(ApplicationProvider.getApplicationContext(), 2))
    }
}
