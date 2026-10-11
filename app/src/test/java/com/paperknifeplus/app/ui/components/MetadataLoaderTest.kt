package com.paperknifeplus.app.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.paperknifeplus.app.testing.TestPdfs
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
class MetadataLoaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    @Test
    fun `a readable PDF gives its properties, blank where it has none`() {
        val bytes = PDDocument.load(TestPdfs.plain()).use { doc ->
            doc.documentInformation.title = "Report"
            doc.documentInformation.author = "Ada"
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val uri = TestPdfs.write(context.cacheDir, bytes, "props.pdf")
        assertEquals(PdfProperties("Report", "Ada", "", "", "", ""), runBlocking { loadMetadata(context, uri, null) })
    }

    @Test
    fun `an encrypted PDF opens with its password`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.userPassword(), "props-locked.pdf")
        assertEquals("", runBlocking { loadMetadata(context, uri, TestPdfs.USER_PASSWORD) }?.title)
        assertNull(runBlocking { loadMetadata(context, uri, "wrong") })
    }

    @Test
    fun `garbage gives null`() {
        val uri = TestPdfs.write(context.cacheDir, TestPdfs.garbage, "props-garbage.pdf")
        assertNull(runBlocking { loadMetadata(context, uri, null) })
    }

    @Test
    fun `a provider without a stream gives null`() {
        assertNull(runBlocking { loadMetadata(context, TestPdfs.nullStreamUri(), null) })
    }
}
