package com.paperknifeplus.app.ui.components

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** What the tools rely on from Android itself rather than the JVM the unit tests run on: its XML stack and its PDF renderer. */
@RunWith(AndroidJUnit4::class)
class ToolPdfDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    @Test
    fun xmpIsRewrittenWithAndroidsXmlParserAndTransformer() {
        val xmp = """
            <?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:pdf="http://ns.adobe.com/pdf/1.3/" pdf:Producer="Old Producer">
            <pdf:Keywords>old</pdf:Keywords></rdf:Description></rdf:RDF></x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.documentCatalog.metadata = PDMetadata(doc, xmp.byteInputStream())
            val original = PdfProperties("", "", "", "old", "", "Old Producer")
            applyProperties(doc, original, PdfProperties("Device Title", "Ada", "", "", "", "New Producer"))
            val result = doc.documentCatalog.metadata.exportXMPMetadata().use { String(it.readBytes(), Charsets.UTF_8) }
            assertTrue(result, result.contains("Device Title"))
            assertTrue(result, result.contains("Ada"))
            assertTrue(result, result.contains("New Producer"))
            assertFalse(result, result.contains("Old Producer"))
            assertFalse(result, result.contains("Keywords"))
            assertTrue(result, result.contains("xmlns:dc=\"http://purl.org/dc/elements/1.1/\""))
        }
    }

    @Test
    fun theRendererOpensAnOwnerPasswordOnlyFileWithoutAPassword() {
        val file = File(context.cacheDir, "owner-only-${System.nanoTime()}.pdf")
        PDDocument().use { doc ->
            repeat(2) { doc.addPage(PDPage()) }
            doc.protect(StandardProtectionPolicy("owner", "", AccessPermission().apply { setCanPrint(false) }).apply { encryptionKeyLength = 128 })
            doc.save(file)
        }
        try {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { assertEquals(2, it.pageCount) }
        } finally {
            file.delete()
        }
    }
}
