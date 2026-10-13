package com.haziaferi.scanknifeplus.scan.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The real pdfbox assembler, with and without a password, with Robolectric's native graphics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfBoxAssemblerTest {
    private val dir: File = Files.createTempDirectory("assembler").toFile()
    private val pages = List(2) { i ->
        File(dir, "$i.jpg").also { f ->
            val b = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 100, 50)) }
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            b.recycle()
        }
    }

    @Before
    fun setUp() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext<Context>())

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun write(password: String?): File = File(dir, "out-${password ?: "plain"}.pdf").also {
        assertTrue(PdfBoxAssembler.write(pages, PdfPageSize.A4, dir, it, password))
    }

    @Test
    fun `without a password the PDF is not encrypted`() {
        PDDocument.load(write(null)).use { doc ->
            assertFalse(doc.isEncrypted)
            assertNull(doc.encryption)
            assertEquals(2, doc.numberOfPages)
        }
    }

    @Test
    fun `the PDF carries no Creator or Producer`() {
        PDDocument.load(write(null)).use { doc ->
            assertNull(doc.documentInformation.creator)
            assertNull(doc.documentInformation.producer)
        }
    }

    @Test
    fun `with a password the PDF is AES-256 as Protect writes it and opens only with that password`() {
        val pdf = write("secret")
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(pdf).close() }
        assertThrows(InvalidPasswordException::class.java) { PDDocument.load(pdf, "Secret").close() }
        PDDocument.load(pdf, "secret").use { doc ->
            assertTrue(doc.isEncrypted)
            assertEquals(5, doc.encryption.version)
            assertEquals(6, doc.encryption.revision)
            assertEquals(256, doc.encryption.length)
            assertTrue(doc.currentAccessPermission.isOwnerPermission) // the same password is the owner password
            assertEquals(2, doc.numberOfPages)
        }
    }

    @Test
    fun `a password SASLprep rejects fails the PDF, and isUsablePassword tells beforehand`() {
        val rejected = "pass\u0007word" // an ASCII control character, which SASLprep prohibits
        assertFalse(PdfBoxAssembler.write(pages, PdfPageSize.A4, dir, File(dir, "rejected.pdf"), rejected))
        assertFalse(ScanExport.isUsablePassword(rejected))
        assertFalse(ScanExport.isUsablePassword("")) // private use
        for (usable in listOf("secret", "пароль", "", null)) assertTrue("$usable", ScanExport.isUsablePassword(usable))
    }

    @Test
    fun `a non-Latin password round-trips`() {
        PDDocument.load(write("пароль"), "пароль").use { assertEquals(2, it.numberOfPages) }
    }

    @Test
    fun `Fit gives each page its image's shape with A4's long edge, the image filling it`() {
        val landscape = File(dir, "wide.jpg").also { f ->
            val b = Bitmap.createBitmap(120, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            b.recycle()
        }
        val pdf = File(dir, "fit.pdf")
        assertTrue(PdfBoxAssembler.write(listOf(pages[0], landscape), PdfPageSize.FIT, dir, pdf, null))
        PDDocument.load(pdf).use { doc ->
            val long = PdfPageSize.A4.height.toFloat()
            val boxes = doc.pages.map { it.mediaBox.width to it.mediaBox.height }
            assertEquals(long * 60 / 80, boxes[0].first, 0.01f)
            assertEquals(long, boxes[0].second, 0.01f)
            assertEquals(long, boxes[1].first, 0.01f)
            assertEquals(long / 3, boxes[1].second, 0.01f)
        }
    }
}
