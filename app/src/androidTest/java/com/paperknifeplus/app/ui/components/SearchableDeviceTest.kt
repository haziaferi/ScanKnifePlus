package com.paperknifeplus.app.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.ocr.TesseractOcrEngine
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Make PDF searchable on the phone with the real Tesseract engine and the platform renderer: an image-only PDF of a benchmark corpus page
 * (docs/ocr-bench/corpus, in the test APK's assets) gets a text layer whose words PDFTextStripper finds, upright and on a rotated page too.
 */
@RunWith(AndroidJUnit4::class)
class SearchableDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "searchable-device-test")

    @Before
    fun setUp() {
        dir.deleteRecursively()
        dir.mkdirs()
        PDFBoxResourceLoader.init(context)
    }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** The truth page's words of five or more letters, which a good read finds nearly all of. */
    private fun truthWords(): Set<String> = instrumentation.context.assets.open("truth/a-english.txt").use { it.reader().readText() }
        .split(Regex("[^\\p{L}]+")).filter { it.length >= 5 }.toSet()

    /**
     * A PDF of one page showing the corpus page as a JPEG and nothing else: A4 upright, or with [rotation] on a landscape mediaBox whose
     * cropBox starts away from the origin, the image drawn so the page shows it upright.
     */
    private fun imagePdf(rotation: Int): File = File(dir, "image-$rotation.pdf").also { file ->
        val bitmap = instrumentation.context.assets.open("clean/a-english.300dpi.png").use { BitmapFactory.decodeStream(it) }!!
        PDDocument().use { doc ->
            val page = if (rotation == 0) {
                PDPage(PDRectangle.A4)
            } else {
                PDPage(PDRectangle(0f, 0f, 900f, 700f)).apply {
                    cropBox = PDRectangle(30f, 50f, PDRectangle.A4.height, PDRectangle.A4.width)
                    this.rotation = rotation
                }
            }
            doc.addPage(page)
            val image = JPEGFactory.createFromImage(doc, bitmap, 0.9f)
            val box = PageBox.of(page)
            PDPageContentStream(doc, page).use {
                it.transform(box.displayMatrix())
                it.drawImage(image, 0f, 0f, box.displayWidth, box.displayHeight)
            }
            doc.save(file)
        }
        bitmap.recycle()
    }

    private fun searchable(source: File): File {
        val out = File(dir, "out-${source.name}")
        val options = SearchableOptions(OcrLanguage.ENGLISH, OcrReading.AUTOMATIC)
        val result = runBlocking {
            makeSearchable(context, Uri.fromFile(source), "", Uri.fromFile(out), options, { TesseractOcrEngine.open(context) }, MutableStateFlow(true))
        }
        assertEquals(SearchableResult(1, 1, 0, emptyList()), result)
        return out
    }

    private fun assertFindsTheWords(pdf: File) {
        val text = PDDocument.load(pdf).use { PDFTextStripper().getText(it) }
        val words = truthWords()
        val found = words.count { it in text }
        assertTrue("found $found of ${words.size} words in: $text", found >= words.size * 0.9)
    }

    @Test
    fun anImageOnlyPageBecomesSearchable() {
        val source = imagePdf(0)
        assertTrue(PDDocument.load(source).use { PDFTextStripper().getText(it) }.isBlank())
        assertFindsTheWords(searchable(source))
    }

    @Test
    fun aRotatedPageWithAnOffsetCropBoxBecomesSearchableWithUprightText() {
        val out = searchable(imagePdf(90))
        assertFindsTheWords(out)
        PDDocument.load(out).use { doc ->
            val box = PageBox.of(doc.getPage(0))
            val glyphs = mutableListOf<TextPosition>()
            object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: List<TextPosition>) {
                    glyphs += textPositions
                }
            }.getText(doc)
            // The title "The Reading Room" sits near the top left of the page as displayed and reads left to right.
            val title = glyphs.first()
            val m = title.textMatrix
            val x = m.translateX + box.left
            val y = m.translateY + box.bottom
            val at = box.pdfToDisplay(x, y)
            val ahead = box.pdfToDisplay(x + m.scaleX, y + m.shearY)
            assertTrue("title at $at", at.x < box.displayWidth / 2 && at.y < box.displayHeight / 4)
            assertTrue("reads left to right", ahead.x - at.x > 0f && abs(ahead.y - at.y) < 0.01f)
        }
    }
}
