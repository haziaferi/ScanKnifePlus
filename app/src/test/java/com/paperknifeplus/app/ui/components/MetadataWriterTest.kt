package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
class MetadataWriterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private val edited = PdfProperties(title = "New Title", author = "", subject = "About it", keywords = "", creator = "", producer = "New Producer")

    /**
     * A PDF whose Info and XMP both hold old values for all six fields, Producer and CreatorTool as XMP attributes; with [xmpOnlyKeywords],
     * Info has no keywords, so only the XMP holds them.
     */
    private fun withXmp(xmpOnlyKeywords: Boolean = false): ByteArray = PDDocument.load(TestPdfs.plain()).use { doc ->
        doc.documentInformation.apply {
            title = "Old Title"
            author = "Old Author"
            subject = "Old Subject"
            keywords = if (xmpOnlyKeywords) null else "old, words"
            creator = "Old Tool"
            producer = "Old Producer"
        }
        doc.documentCatalog.metadata = PDMetadata(doc, OLD_XMP.byteInputStream(Charsets.UTF_8))
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    /** Reads [source]'s properties and saves what [edit] makes of them, as the Metadata tool does, then reloads the result. */
    private fun save(source: ByteArray, edit: (PdfProperties) -> PdfProperties = { edited }): PDDocument {
        val input = TestPdfs.write(context.cacheDir, source, "meta-in.pdf")
        val output = File(context.cacheDir, "meta-out.pdf").apply { delete() }
        val original = runBlocking { loadMetadata(context, input, null) }!!
        saveProperties(context, input, "", Uri.fromFile(output), original, edit(original))
        return PDDocument.load(output)
    }

    private fun PDDocument.xmp(): org.w3c.dom.Document {
        val bytes = documentCatalog.metadata.exportXMPMetadata().use { it.readBytes() }
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(bytes.inputStream())
    }

    /** Text of the XMP property [name] in [namespace], whether written as an attribute or as an element; null when absent. */
    private fun xmpValue(xmp: org.w3c.dom.Document, namespace: String, name: String): String? {
        val descriptions = xmp.getElementsByTagNameNS(RDF, "Description")
        for (i in 0 until descriptions.length) {
            val description = descriptions.item(i) as Element
            if (description.hasAttributeNS(namespace, name)) return description.getAttributeNS(namespace, name)
        }
        val elements = xmp.getElementsByTagNameNS(namespace, name)
        return if (elements.length == 0) null else elements.item(0).textContent.trim()
    }

    @Test
    fun `blank fields clear the Info entry and no default creator, producer or author comes back`() {
        save(withXmp()).use { doc ->
            val info = doc.documentInformation
            assertEquals("New Title", info.title)
            assertNull(info.author)
            assertEquals("About it", info.subject)
            assertNull(info.keywords)
            assertNull(info.creator)
            assertEquals("New Producer", info.producer)
        }
    }

    @Test
    fun `XMP says the same as Info, with cleared fields removed`() {
        save(withXmp()).use { doc ->
            val bytes = doc.documentCatalog.metadata.exportXMPMetadata().use { it.readBytes() }
            assertTrue(String(bytes, Charsets.UTF_8).contains("xpacket begin"))
            val xmp = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(bytes.inputStream())
            assertEquals("New Title", xmpValue(xmp, DC, "title"))
            assertNull(xmpValue(xmp, DC, "creator"))
            assertEquals("About it", xmpValue(xmp, DC, "description"))
            assertNull(xmpValue(xmp, PDF, "Keywords"))
            assertNull(xmpValue(xmp, XMP, "CreatorTool"))
            assertEquals("New Producer", xmpValue(xmp, PDF, "Producer"))
            assertEquals("x-default", (xmp.getElementsByTagNameNS(RDF, "li").item(0) as Element).getAttribute("xml:lang"))
        }
    }

    @Test
    fun `a save without edits keeps XMP values that Info does not have`() {
        save(withXmp(xmpOnlyKeywords = true)) { it }.use { doc ->
            assertEquals("old, words", xmpValue(doc.xmp(), PDF, "Keywords"))
            assertEquals("Old Title", xmpValue(doc.xmp(), DC, "title"))
            assertNull(doc.documentInformation.keywords)
        }
    }

    @Test
    fun `only edited fields change, in Info and XMP`() {
        save(withXmp(xmpOnlyKeywords = true)) { it.copy(title = "Only This") }.use { doc ->
            assertEquals("Only This", doc.documentInformation.title)
            assertEquals("Only This", xmpValue(doc.xmp(), DC, "title"))
            assertEquals("old, words", xmpValue(doc.xmp(), PDF, "Keywords"))
            assertEquals("Old Tool", xmpValue(doc.xmp(), XMP, "CreatorTool"))
            assertEquals("Old Author", doc.documentInformation.author)
        }
    }

    @Test
    fun `a file without XMP gets none`() {
        save(TestPdfs.plain()).use { doc ->
            assertNull(doc.documentCatalog.metadata)
            assertEquals("New Title", doc.documentInformation.title)
        }
    }

    private companion object {
        const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        const val DC = "http://purl.org/dc/elements/1.1/"
        const val PDF = "http://ns.adobe.com/pdf/1.3/"
        const val XMP = "http://ns.adobe.com/xap/1.0/"

        val OLD_XMP = """
            <?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="$RDF">
                <rdf:Description rdf:about="" xmlns:dc="$DC" xmlns:pdf="$PDF" xmlns:xmp="$XMP"
                    pdf:Producer="Old Producer" xmp:CreatorTool="Old Tool">
                  <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Old Title</rdf:li></rdf:Alt></dc:title>
                  <dc:creator><rdf:Seq><rdf:li>Old Author</rdf:li></rdf:Seq></dc:creator>
                  <dc:description><rdf:Alt><rdf:li xml:lang="x-default">Old Subject</rdf:li></rdf:Alt></dc:description>
                  <pdf:Keywords>old, words</pdf:Keywords>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()
    }
}
