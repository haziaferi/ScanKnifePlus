package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ExtractedImagesTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    @Before
    fun setUp() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    private val dct = COSName.DCT_DECODE.name
    private val flate = COSName.FLATE_DECODE.name
    private val rgb = COSName.DEVICERGB.name
    private val gray = COSName.DEVICEGRAY.name

    private fun traits(
        vararg filters: String,
        colorSpace: String? = rgb,
        remappingDecode: Boolean = false,
        masked: Boolean = false,
        stencil: Boolean = false
    ) = ImageTraits(filters.toList(), colorSpace, remappingDecode, masked, stencil)

    /** What [writeImages] does with [t]: the raw output if any, else the decoded one. */
    private fun chosen(t: ImageTraits, decoded: Boolean): ImageOutput = rawOutput(t) ?: decodedOutput(t, decoded)

    @Test
    fun `plain gray and RGB JPEGs are copied as they are`() {
        assertEquals(ImageOutput.RAW_JPEG, chosen(traits(dct), decoded = true))
        assertEquals(ImageOutput.RAW_JPEG, chosen(traits(dct, colorSpace = gray), decoded = false))
        assertEquals(ImageOutput.RAW_JPEG, chosen(traits(flate, dct), decoded = true))
        assertEquals("jpg", ImageOutput.RAW_JPEG.extension)
    }

    @Test
    fun `JPEGs a viewer would show differently are decoded and re-encoded`() {
        assertEquals(ImageOutput.JPEG, chosen(traits(dct, colorSpace = COSName.DEVICECMYK.name), decoded = true))
        assertEquals(ImageOutput.JPEG, chosen(traits(dct, remappingDecode = true), decoded = true))
        assertEquals(ImageOutput.PNG, chosen(traits(dct, masked = true), decoded = true))
        assertEquals(ImageOutput.SKIP, chosen(traits(dct, colorSpace = COSName.DEVICECMYK.name), decoded = false))
    }

    @Test
    fun `JPEG 2000 is always copied`() {
        assertEquals(ImageOutput.RAW_JPX, chosen(traits(COSName.JPX_DECODE.name, colorSpace = null), decoded = false))
        assertEquals("jp2", ImageOutput.RAW_JPX.extension)
    }

    @Test
    fun `lossless, transparent and stencil images become PNG`() {
        assertEquals(ImageOutput.PNG, chosen(traits(flate), decoded = true))
        assertEquals(ImageOutput.PNG, chosen(traits(), decoded = true))
        assertEquals(ImageOutput.PNG, chosen(traits(flate, masked = true), decoded = true))
        assertEquals(ImageOutput.PNG, chosen(traits(COSName.CCITTFAX_DECODE.name, colorSpace = null, stencil = true), decoded = true))
        assertEquals("png", ImageOutput.PNG.extension)
    }

    @Test
    fun `CCITT images become PNG, never JPEG data in a tiff file`() {
        val ccitt = traits(COSName.CCITTFAX_DECODE.name, colorSpace = gray)
        assertNull(rawOutput(ccitt))
        assertEquals(ImageOutput.PNG, decodedOutput(ccitt, decoded = true))
    }

    @Test
    fun `undecodable images that are no image files are skipped`() {
        assertEquals(ImageOutput.SKIP, chosen(traits(flate), decoded = false))
        assertEquals(ImageOutput.SKIP, chosen(traits(COSName.CCITTFAX_DECODE.name, colorSpace = gray), decoded = false))
    }

    @Test
    fun `traits are read from the image dictionary`() {
        PDDocument().use { doc ->
            val stream = doc.document.createCOSStream().apply {
                setItem(COSName.FILTER, COSArray().apply { add(COSName.FLATE_DECODE); add(COSName.DCT_DECODE) })
                setItem(COSName.COLORSPACE, COSArray().apply {
                    add(COSName.ICCBASED)
                    add(doc.document.createCOSStream().apply { setInt(COSName.N, 3) })
                })
                setItem(COSName.DECODE, COSArray().apply { listOf(1f, 0f, 1f, 0f, 1f, 0f).forEach { add(com.tom_roush.pdfbox.cos.COSFloat(it)) } })
                setItem(COSName.SMASK, doc.document.createCOSStream())
            }
            val t = imageTraits(PDImageXObject(com.tom_roush.pdfbox.pdmodel.common.PDStream(stream), null))
            assertEquals(listOf(COSName.FLATE_DECODE.name, COSName.DCT_DECODE.name), t.filters)
            assertEquals(rgb, t.colorSpace)
            assertEquals(true, t.remappingDecode)
            assertEquals(true, t.masked)
        }
    }

    @Test
    fun `a Flate-wrapped JPEG is written as its JPEG bytes, named jpg`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 0xFF.toByte(), 0xD9.toByte())
        val zipped = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(jpeg) } }.toByteArray()
        val entries = PDDocument().use { doc ->
            val image: COSStream = doc.document.createCOSStream().apply {
                setItem(COSName.TYPE, COSName.XOBJECT)
                setItem(COSName.SUBTYPE, COSName.IMAGE)
                setInt(COSName.WIDTH, 2)
                setInt(COSName.HEIGHT, 2)
                setInt(COSName.BITS_PER_COMPONENT, 8)
                setItem(COSName.COLORSPACE, COSName.DEVICERGB)
                createRawOutputStream().use { it.write(zipped) }
                setItem(COSName.FILTER, COSArray().apply { add(COSName.FLATE_DECODE); add(COSName.DCT_DECODE) })
            }
            val page = PDPage().apply { resources = PDResources() }
            page.resources.put(COSName.getPDFName("Im1"), PDImageXObject(com.tom_roush.pdfbox.pdmodel.common.PDStream(image), null))
            doc.addPage(page)
            val zip = ByteArrayOutputStream()
            val counts = ZipOutputStream(zip).use { writeImages(doc, it) }
            assertEquals(ExtractCounts(written = 1, skipped = 0), counts)
            readZip(zip.toByteArray())
        }
        assertEquals(listOf("asset_1_p1.jpg"), entries.keys.toList())
        assertArrayEquals(jpeg, entries.values.single())
    }

    @Test
    fun `an image that cannot be decoded is skipped, not dumped`() {
        val counts = PDDocument().use { doc ->
            val broken: COSStream = doc.document.createCOSStream().apply {
                setItem(COSName.TYPE, COSName.XOBJECT)
                setItem(COSName.SUBTYPE, COSName.IMAGE)
                setInt(COSName.WIDTH, 2)
                setInt(COSName.HEIGHT, 2)
                setInt(COSName.BITS_PER_COMPONENT, 8)
                setItem(COSName.COLORSPACE, COSName.DEVICERGB)
                createRawOutputStream().use { it.write("not zlib data".toByteArray()) }
                setItem(COSName.FILTER, COSName.FLATE_DECODE)
            }
            val page = PDPage().apply { resources = PDResources() }
            page.resources.put(COSName.getPDFName("Im1"), PDImageXObject(com.tom_roush.pdfbox.pdmodel.common.PDStream(broken), null))
            doc.addPage(page)
            ZipOutputStream(ByteArrayOutputStream()).use { writeImages(doc, it) }
        }
        assertEquals(ExtractCounts(written = 0, skipped = 1), counts)
    }

    /** A page holding one fake raw JPEG per width and height in [sizes], so [writeImages] copies them without decoding. */
    private fun jpegPage(doc: PDDocument, vararg sizes: Pair<Int, Int>) {
        val page = PDPage().apply { resources = PDResources() }
        sizes.forEachIndexed { i, (width, height) ->
            val image: COSStream = doc.document.createCOSStream().apply {
                setItem(COSName.TYPE, COSName.XOBJECT)
                setItem(COSName.SUBTYPE, COSName.IMAGE)
                setInt(COSName.WIDTH, width)
                setInt(COSName.HEIGHT, height)
                setInt(COSName.BITS_PER_COMPONENT, 8)
                setItem(COSName.COLORSPACE, COSName.DEVICERGB)
                createRawOutputStream().use { it.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), i.toByte(), 0xFF.toByte(), 0xD9.toByte())) }
                setItem(COSName.FILTER, COSName.DCT_DECODE)
            }
            page.resources.put(COSName.getPDFName("Im$i"), PDImageXObject(com.tom_roush.pdfbox.pdmodel.common.PDStream(image), null))
        }
        doc.addPage(page)
    }

    @Test
    fun `images below the minimum size are left out and counted apart from unreadable ones`() {
        PDDocument().use { doc ->
            // Too small both ways, too narrow, too short, then two large enough.
            jpegPage(doc, 10 to 20, 49 to 300, 300 to 49, 50 to 50, 300 to 310)
            val zip = ByteArrayOutputStream()
            val counts = ZipOutputStream(zip).use { writeImages(doc, it, minSize = 50) }
            assertEquals(ExtractCounts(written = 2, skipped = 0, filtered = 3), counts)
            assertEquals(listOf("asset_1_p1.jpg", "asset_2_p1.jpg"), readZip(zip.toByteArray()).keys.toList())
        }
    }

    @Test
    fun `without a minimum size every image is written, as before`() {
        PDDocument().use { doc ->
            jpegPage(doc, 1 to 1, 10 to 20, 300 to 310)
            val counts = ZipOutputStream(ByteArrayOutputStream()).use { writeImages(doc, it) }
            assertEquals(ExtractCounts(written = 3, skipped = 0, filtered = 0), counts)
        }
    }

    @Test
    fun `filtered images are named in the result and explain an empty ZIP`() {
        assertEquals("Found 3 assets in document; 2 images below the minimum size left out", ExtractCounts(3, 0, 2).summary(res))
        assertEquals("Saved 1 of 2 images; 1 image could not be read; 1 image below the minimum size left out", ExtractCounts(1, 1, 1).summary(res))
        assertEquals("All 4 images of the PDF are below the minimum size", ExtractCounts(0, 0, 4).emptyReason(res))
        assertEquals("The PDF has 1 image, but it could not be read", ExtractCounts(0, 1, 3).emptyReason(res))
    }

    @Test
    fun `the result tells how many images were left out, and an empty ZIP says why`() {
        assertEquals("Found 3 assets in document", ExtractCounts(3, 0).summary(res))
        assertEquals("Saved 3 of 5 images; 2 images could not be read", ExtractCounts(3, 2).summary(res))
        assertEquals("Saved 2 of 3 images; 1 image could not be read", ExtractCounts(2, 1).summary(res))
        assertNull(ExtractCounts(1, 4).emptyReason(res))
        assertEquals("No images found in PDF", ExtractCounts(0, 0).emptyReason(res))
        assertEquals("The PDF has 2 images, but none could be read", ExtractCounts(0, 2).emptyReason(res))
        assertEquals("Found 1 asset in document", ExtractCounts(1, 0).summary(res))
    }

    @Test
    @Config(qualifiers = "it")
    fun `on an Italian phone the result reads in Italian, singular and plural`() {
        assertEquals("Salvata 1 immagine su 3; 2 immagini non sono leggibili", ExtractCounts(1, 2).summary(res))
        assertEquals("Trovati 3 elementi nel documento; 1 immagine esclusa perché sotto la dimensione minima", ExtractCounts(3, 0, 1).summary(res))
        assertEquals("Il PDF ha 1 immagine, ma non è leggibile", ExtractCounts(0, 1).emptyReason(res))
        assertEquals("Nessuna immagine trovata nel PDF", ExtractCounts(0, 0).emptyReason(res))
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }
}
