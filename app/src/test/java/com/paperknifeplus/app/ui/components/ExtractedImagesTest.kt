package com.paperknifeplus.app.ui.components

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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class ExtractedImagesTest {
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
