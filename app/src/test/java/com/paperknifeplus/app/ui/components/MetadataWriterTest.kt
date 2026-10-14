package com.paperknifeplus.app.ui.components

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
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
import java.util.Calendar
import java.util.TimeZone
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
            creationDate = OLD_CREATED
            modificationDate = OLD_CREATED
        }
        doc.documentCatalog.metadata = PDMetadata(doc, OLD_XMP.byteInputStream(Charsets.UTF_8))
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    /** Reads [source]'s properties and saves what [edit] makes of them with [extras], as the Metadata tool does, then reloads the result. */
    private fun save(source: ByteArray, extras: MetadataExtras = MetadataExtras(), edit: (PdfProperties) -> PdfProperties = { edited }): PDDocument {
        val input = TestPdfs.write(context.cacheDir, source, "meta-in.pdf")
        val output = File(context.cacheDir, "meta-out.pdf").apply { delete() }
        val original = runBlocking { loadMetadata(context, input, null) }!!
        saveProperties(context, input, "", Uri.fromFile(output), original, edit(original), extras)
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

    @Test
    fun `edited dates go into Info and XMP, and the other date stays`() {
        val created = date(2024, 5, 6, 7, 8, 9, "GMT+02:00")
        save(withXmp(), MetadataExtras(created = DateEdit.Change(created))) { it }.use { doc ->
            assertEquals(created.timeInMillis, doc.documentInformation.creationDate.timeInMillis)
            assertEquals(OLD_CREATED.timeInMillis, doc.documentInformation.modificationDate.timeInMillis)
            assertEquals("2024-05-06T07:08:09+02:00", xmpValue(doc.xmp(), XMP, "CreateDate"))
            assertEquals("2001-02-03T04:05:06Z", xmpValue(doc.xmp(), XMP, "ModifyDate"))
            assertEquals("Old Title", doc.documentInformation.title)
        }
    }

    @Test
    fun `cleared dates leave Info and XMP`() {
        save(withXmp(), MetadataExtras(created = DateEdit.Change(null), modified = DateEdit.Change(null))) { it }.use { doc ->
            assertNull(doc.documentInformation.creationDate)
            assertNull(doc.documentInformation.modificationDate)
            assertNull(doc.documentInformation.cosObject.getItem(COSName.CREATION_DATE))
            assertNull(xmpValue(doc.xmp(), XMP, "CreateDate"))
            assertNull(xmpValue(doc.xmp(), XMP, "ModifyDate"))
            assertEquals("Old Tool", xmpValue(doc.xmp(), XMP, "CreatorTool"))
        }
    }

    @Test
    fun `removing XMP drops the stream and still applies the edits to Info`() {
        save(withXmp(), MetadataExtras(modified = DateEdit.Change(date(2020, 1, 2, 3, 4, 5, "UTC")), removeXmp = true)).use { doc ->
            assertNull(doc.documentCatalog.metadata)
            assertNull(doc.documentCatalog.cosObject.getItem(COSName.METADATA))
            assertEquals("New Title", doc.documentInformation.title)
            assertEquals(2020, doc.documentInformation.modificationDate.get(Calendar.YEAR))
        }
    }

    @Test
    fun `a date edit gives a file without XMP none`() {
        save(TestPdfs.plain(), MetadataExtras(created = DateEdit.Change(OLD_CREATED))) { it }.use { doc ->
            assertNull(doc.documentCatalog.metadata)
            assertEquals(OLD_CREATED.timeInMillis, doc.documentInformation.creationDate.timeInMillis)
        }
    }

    private companion object {
        const val RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
        const val DC = "http://purl.org/dc/elements/1.1/"
        const val PDF = "http://ns.adobe.com/pdf/1.3/"
        const val XMP = "http://ns.adobe.com/xap/1.0/"

        val OLD_CREATED: Calendar = date(2001, 2, 3, 4, 5, 6, "UTC")

        fun date(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, zone: String): Calendar =
            Calendar.getInstance(TimeZone.getTimeZone(zone)).apply {
                clear()
                set(year, month - 1, day, hour, minute, second)
            }

        val OLD_XMP = """
            <?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="$RDF">
                <rdf:Description rdf:about="" xmlns:dc="$DC" xmlns:pdf="$PDF" xmlns:xmp="$XMP"
                    pdf:Producer="Old Producer" xmp:CreatorTool="Old Tool" xmp:CreateDate="2001-02-03T04:05:06Z">
                  <xmp:ModifyDate>2001-02-03T04:05:06Z</xmp:ModifyDate>
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
