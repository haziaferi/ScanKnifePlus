package com.haziaferi.scanknifeplus.ui.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.scan.export.SavedPdf
import com.haziaferi.scanknifeplus.scanner.export.PdfPageSize
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A scan saved with Make searchable on the phone, with the real Tesseract engine: a rendered page imported into the flow, saved as a PDF
 * whose extracted text holds the page's words.
 */
@RunWith(AndroidJUnit4::class)
class SearchableSaveDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "searchable-save-test")
    private lateinit var model: ScanFlowModel
    @Volatile private var saved: SavedPdf? = null

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
        PDFBoxResourceLoader.init(context)
    }

    @After
    fun cleanUp() {
        // A save ends the flow itself; one that did not get there is left without keeping its page.
        if (::model.isInitialized && saved == null) {
            var left = false
            main { model.leave(keep = false) { left = true } }
            waitFor("the session to end") { main { left }.takeIf { it } }
        }
        dir.deleteRecursively()
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun <T : Any> waitFor(what: String, timeoutMs: Long = 120_000, value: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            value()?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    /** An A4-shaped page at about 200 DPI with three lines of large black text on white. */
    private fun page(lines: List<String>): File = File(dir, "page.jpg").also { f ->
        val bitmap = Bitmap.createBitmap(1654, 2339, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 72f
            typeface = Typeface.SERIF
        }
        lines.forEachIndexed { i, line -> canvas.drawText(line, 160f, 400f + i * 140f, paint) }
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
    }

    @Test
    fun aSearchableSaveHoldsThePageWords() {
        val image = page(listOf("Invoice number 4821", "Receipt for the scanner", "Searchable text works"))
        model = main { ScanFlowModel(context) }
        main { model.importImages(context.contentResolver, listOf(Uri.fromFile(image))) }
        waitFor("the imported page") { main { model.pages.size == 1 && model.canSave }.takeIf { it } }

        val pdf = File(dir, "out.pdf").apply { createNewFile() }
        val options = SaveOptions(PdfPageSize.A4, "", TextOptions(OcrLanguage.ENGLISH, OcrReading.AUTOMATIC, txt = false))
        main { model.save(Uri.fromFile(pdf), options) { saved = it } }
        waitFor("the searchable save") { main { saved } }

        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }
        for (word in listOf("Invoice", "4821", "Receipt", "scanner", "Searchable")) assertTrue("$word in: $text", word in text)
    }
}
