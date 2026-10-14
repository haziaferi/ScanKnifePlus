package com.paperknifeplus.app.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Redact on the platform renderer: a turned page keeps its turn, and the box lands where it was drawn. */
@RunWith(AndroidJUnit4::class)
class RedactionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() = PDFBoxResourceLoader.init(context)

    private fun file(name: String) = File(context.cacheDir, "$name-${System.nanoTime()}.pdf")

    private fun render(file: File, width: Int, height: Int): Bitmap =
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            renderer.openPage(0).use { page ->
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }

    private fun assertColour(expected: Int, pixel: Int, what: String) {
        val near = abs(Color.red(pixel) - Color.red(expected)) <= 12 && abs(Color.green(pixel) - Color.green(expected)) <= 12 &&
            abs(Color.blue(pixel) - Color.blue(expected)) <= 12
        assertTrue("$what: ${Integer.toHexString(pixel)}", near)
    }

    @Test
    fun aTurnedPageIsRedactedInPlace() {
        val source = file("redact-src")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.LETTER)
            page.rotation = 90
            page.setContents(PDStream(doc, "0 0 1 rg 0 0 612 792 re f".byteInputStream()))
            doc.addPage(page)
            doc.save(source)
        }
        val output = file("redact-out")
        val box = PDRectangle(100f, 100f, 100f, 100f)
        val report = runBlocking {
            redactPdf(context, Uri.fromFile(source), Uri.fromFile(output), null, mapOf(0 to listOf(box)), null, RedactFill.BLACK)
        }
        assertEquals(listOf(0), report.pages)

        PDDocument.load(output).use { doc ->
            val page = doc.getPage(0)
            assertEquals(90, page.rotation)
            val image = page.resources.getXObject(page.resources.xObjectNames.single()) as PDImageXObject
            // 300 DPI of the page as displayed: 11 x 8.5 inches.
            assertEquals(3300, image.width)
            assertEquals(2550, image.height)
        }

        // Shown turned at 1 pixel a point, the unrotated point (150, 150) is at (150, 150) from the top left.
        val shown = render(output, 792, 612)
        assertColour(Color.BLACK, shown.getPixel(150, 150), "inside the box")
        assertColour(Color.BLUE, shown.getPixel(400, 300), "outside the box")
        assertColour(Color.BLUE, shown.getPixel(150, 250), "just past the box")
        source.delete()
        output.delete()
    }
}
