package com.haziaferi.scanknifeplus.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import com.haziaferi.scanknifeplus.scan.capture.ImageCodec
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import com.haziaferi.scanknifeplus.scanner.store.StoredImage
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Tesseract engine on the benchmark corpus (docs/ocr-bench/corpus, packed into the test APK's assets), made into stored-page-like JPEGs:
 * the 300 DPI clean renders fitted to the 2400 px page cap at page quality. Timings and memory go to logcat under [TAG].
 */
@RunWith(AndroidJUnit4::class)
class TesseractOcrEngineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "ocr-engine-test").apply { mkdirs() }
    private val engine = TesseractOcrEngine.open(context)

    @After
    fun cleanUp() {
        engine.close()
        dir.deleteRecursively()
    }

    private fun <T : Any> notNull(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun corpus(page: String): Bitmap =
        instrumentation.context.assets.open("clean/$page.300dpi.png").use { BitmapFactory.decodeStream(it) }!!

    private fun truth(page: String): String = instrumentation.context.assets.open("truth/$page.txt").use { it.reader().readText() }

    /** [bitmap] turned clockwise by [turn], fitted to the page cap and written at page quality, as the scanner stores a page. */
    private fun stored(name: String, bitmap: Bitmap, turn: Int = 0): File {
        val turned = if (turn == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { setRotate(turn.toFloat()) }, false)
        val fitted = StoredImage.fitted(turned.width, turned.height, StoredImage.PAGE_MAX_EDGE)
        val scaled = Bitmap.createScaledBitmap(turned, fitted.width, fitted.height, true)
        return File(dir, "$name.jpg").also { assertTrue(ImageCodec.writeJpeg(scaled, it, StoredImage.PAGE_QUALITY)) }
    }

    private fun normalised(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Character error rate of [actual] against [expected], whitespace collapsed (as docs/ocr-bench/score.py scores). */
    private fun cer(expected: String, actual: String): Double {
        val a = normalised(expected)
        val b = normalised(actual)
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) current[j] = min(min(current[j - 1], previous[j]) + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            previous = current
        }
        return previous[b.length].toDouble() / a.length
    }

    /** Reads [page] while sampling the native heap; logs the time and the heap's growth. */
    private fun timedRead(label: String, page: File, language: OcrLanguage, reading: OcrReading = OcrReading.AUTOMATIC): OcrPage? {
        System.gc()
        val base = Debug.getNativeHeapAllocatedSize()
        val peak = AtomicLong(base)
        val sampling = AtomicBoolean(true)
        val sampler = thread {
            while (sampling.get()) {
                peak.accumulateAndGet(Debug.getNativeHeapAllocatedSize()) { a, b -> max(a, b) }
                SystemClock.sleep(10)
            }
        }
        val start = SystemClock.elapsedRealtime()
        val result = engine.read(page, A4_LONG_MM, language, reading)
        val ms = SystemClock.elapsedRealtime() - start
        sampling.set(false)
        sampler.join()
        val pss = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
        Log.i(TAG, "$label: $ms ms, native +${(peak.get() - base) / 1_000_000} MB peak, PSS ${pss / 1000} MB, ${result?.words?.size} words")
        return result
    }

    @Test
    fun englishAndItalianPagesReadWithinTheBenchmarksError() {
        // results.md and questions.md (question 3, clean renders, PSM 3 at 200 DPI): English 0.0% and Italian 0.4% CER. The stored page is a
        // JPEG of a downscaled render, so the bound leaves room for that loss while still failing on a wrong language, scale or turn.
        for ((page, language) in listOf("a-english" to OcrLanguage.ENGLISH, "b-italian" to OcrLanguage.ITALIAN)) {
            val read = notNull(timedRead(page, stored(page, corpus(page)), language))
            val error = cer(truth(page), read.text())
            Log.i(TAG, "$page CER ${"%.4f".format(error)}, turned ${read.turnedClockwise}, mean confidence ${read.meanConfidence}")
            assertTrue("$page CER $error", error < MAX_CER)
            assertEquals(0, read.turnedClockwise)
        }
    }

    @Test
    fun aSidewaysPageIsTurnedAndItsBoxesLandWhereTheUprightPagesDo() {
        val bitmap = corpus("a-english")
        val upright = notNull(timedRead("upright", stored("upright", bitmap), OcrLanguage.ENGLISH))
        val sideways = notNull(timedRead("sideways", stored("sideways", bitmap, turn = 90), OcrLanguage.ENGLISH))
        assertEquals(270, sideways.turnedClockwise)
        // The same words, give or take a misread letter (one in 276 differed on the 6T).
        assertEquals(upright.words.size, sideways.words.size)
        assertTrue(upright.words.zip(sideways.words).count { (u, s) -> u.text != s.text } <= 3)
        // Boxes are fractions of the page as passed in, which is the upright page turned 90 degrees clockwise.
        for ((u, s) in upright.words.zip(sideways.words)) {
            val expected = floatArrayOf(1 - u.bottom, u.left, 1 - u.top, u.right)
            val actual = floatArrayOf(s.left, s.top, s.right, s.bottom)
            for (i in 0 until 4) assertTrue("${s.text}: $i ${expected[i]} vs ${actual[i]}", abs(expected[i] - actual[i]) < BOX_TOLERANCE)
        }
    }

    @Test
    fun aBlankPageHasNoWordsAndNoTurn() {
        val blank = Bitmap.createBitmap(1697, 2400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val read = notNull(engine.read(stored("blank", blank), A4_LONG_MM, OcrLanguage.ENGLISH, OcrReading.AUTOMATIC))
        assertEquals(emptyList<OcrWord>(), read.words)
        assertEquals(0, read.turnedClockwise)
    }

    @Test
    fun receiptsKeepTheReceiptsLinesInOrderBetterThanAutomatic() {
        val cut = receipt()
        // The render is 300 DPI, so the cut-out's real length follows from its pixels.
        val longEdgeMm = max(cut.width, cut.height) / 300.0 * 25.4
        val page = stored("receipt", cut)
        val automatic = cer(truth("d-receipt"), notNull(engine.read(page, longEdgeMm, OcrLanguage.ITALIAN, OcrReading.AUTOMATIC)).text())
        val receipts = cer(truth("d-receipt"), notNull(engine.read(page, longEdgeMm, OcrLanguage.ITALIAN, OcrReading.RECEIPTS)).text())
        Log.i(TAG, "receipt CER automatic ${"%.4f".format(automatic)}, receipts ${"%.4f".format(receipts)}")
        // Measured on the 6T: automatic 0.35 (prices read as a separate column), receipts under 0.01.
        assertTrue("receipts $receipts", receipts < MAX_CER)
        assertTrue("automatic $automatic", automatic > receipts + 0.1)
    }

    /**
     * The receipt column of `d-receipt` cut out with a small margin and stretched to twice its width, a wide till receipt whose prices sit
     * far from their items: the clean column as rendered is narrow enough that automatic layout reads it in order too.
     */
    private fun receipt(): Bitmap {
        val page = corpus("d-receipt")
        val pixels = IntArray(page.width * page.height).also { page.getPixels(it, 0, page.width, 0, 0, page.width, page.height) }
        var left = page.width
        var top = page.height
        var right = 0
        var bottom = 0
        for (y in 0 until page.height) for (x in 0 until page.width) if (Color.red(pixels[y * page.width + x]) < 128) {
            left = min(left, x)
            top = min(top, y)
            right = max(right, x)
            bottom = max(bottom, y)
        }
        val margin = 60
        left = max(0, left - margin)
        top = max(0, top - margin)
        right = min(page.width - 1, right + margin)
        bottom = min(page.height - 1, bottom + margin)
        val column = Bitmap.createBitmap(page, left, top, right - left + 1, bottom - top + 1)
        return Bitmap.createScaledBitmap(column, column.width * 2, column.height, true)
    }

    @Test
    fun cancelStopsAReadQuickly() {
        val page = stored("cancel", corpus("b-italian"))
        val full = SystemClock.elapsedRealtime().let { start -> notNull(engine.read(page, A4_LONG_MM, OcrLanguage.ITALIAN, OcrReading.AUTOMATIC)); SystemClock.elapsedRealtime() - start }
        var result: OcrPage? = OcrPage(emptyList())
        var returnedAt = 0L
        val reader = thread { result = engine.read(page, A4_LONG_MM, OcrLanguage.ITALIAN, OcrReading.AUTOMATIC); returnedAt = SystemClock.elapsedRealtime() }
        // Late in the read, when recognition is under way.
        SystemClock.sleep(full * 2 / 3)
        val cancelledAt = SystemClock.elapsedRealtime()
        engine.cancel()
        reader.join()
        Log.i(TAG, "cancel: full read $full ms, returned ${returnedAt - cancelledAt} ms after cancel")
        assertNull(result)
        assertTrue("returned ${returnedAt - cancelledAt} ms after cancel", returnedAt - cancelledAt < MAX_CANCEL_MS)
        // A cancel ends that read only: the next one reads.
        notNull(engine.read(page, A4_LONG_MM, OcrLanguage.ITALIAN, OcrReading.AUTOMATIC))
    }

    @Test
    fun theEngineStillWorksAfterCloseAndOpen() {
        val page = stored("reopen", corpus("a-english"))
        val first = notNull(engine.read(page, A4_LONG_MM, OcrLanguage.ENGLISH, OcrReading.AUTOMATIC))
        engine.close()
        val second = TesseractOcrEngine.open(context).use { it.read(page, A4_LONG_MM, OcrLanguage.ENGLISH, OcrReading.AUTOMATIC) }
        assertEquals(first, second)
        // Switching language on one engine re-initialises it.
        val italian = TesseractOcrEngine.open(context).use {
            it.read(page, A4_LONG_MM, OcrLanguage.ENGLISH, OcrReading.AUTOMATIC)
            it.read(stored("italian", corpus("b-italian")), A4_LONG_MM, OcrLanguage.ITALIAN, OcrReading.AUTOMATIC)
        }
        assertTrue(cer(truth("b-italian"), notNull(italian).text()) < MAX_CER)
    }

    @Test
    fun anUnreadableFileGivesNull() {
        val junk = File(dir, "junk.jpg").apply { writeText("not a jpeg") }
        assertNull(engine.read(junk, A4_LONG_MM, OcrLanguage.ENGLISH, OcrReading.AUTOMATIC))
        assertNull(engine.read(File(dir, "missing.jpg"), A4_LONG_MM, OcrLanguage.ENGLISH, OcrReading.AUTOMATIC))
    }

    /** Measures the cancel approach: recognition through getHOCRText(0) gives the same words as getUTF8Text, at the logged extra cost. */
    @Test
    fun hocrRecognitionGivesTheSameWordsAsPlainRecognition() {
        val path = notNull(TessData.install(context)).absolutePath
        val image = ImageCodec.decodeScaled(ImageSource.of(stored("hocr", corpus("b-italian"))), 0)!!
        val grey = OcrImages.grey(image).let {
            val size = OcrImages.targetSize(it.width, it.height, A4_LONG_MM, TesseractOcrEngine.DPI)
            OcrImages.resized(it, size.width, size.height)
        }
        val tess = TessBaseAPI()
        try {
            assertTrue(tess.init(path, "ita", TessBaseAPI.OEM_LSTM_ONLY))
            fun run(recognise: () -> Unit): Pair<Long, List<String>> {
                tess.setVariable("user_defined_dpi", "200")
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                tess.setImage(grey.pixels, grey.width, grey.height, 1, grey.width)
                val start = SystemClock.elapsedRealtime()
                recognise()
                val ms = SystemClock.elapsedRealtime() - start
                val words = mutableListOf<String>()
                val iter = tess.resultIterator!!
                iter.begin()
                do {
                    iter.getUTF8Text(PageIteratorLevel.RIL_WORD)?.let { words += "$it ${iter.confidence(PageIteratorLevel.RIL_WORD)} ${iter.getBoundingBox(PageIteratorLevel.RIL_WORD).joinToString()}" }
                } while (iter.next(PageIteratorLevel.RIL_WORD))
                iter.delete()
                tess.clear()
                return ms to words
            }
            val times = mutableListOf<Pair<Long, Long>>()
            repeat(3) {
                val plain = run { tess.getUTF8Text() }
                val hocr = run { tess.getHOCRText(0) }
                assertEquals(plain.second, hocr.second)
                times += plain.first to hocr.first
            }
            Log.i(TAG, "getUTF8Text vs getHOCRText(0) ms: $times")
        } finally {
            tess.recycle()
        }
    }

    private companion object {
        const val TAG = "OcrEngineDeviceTest"
        const val A4_LONG_MM = 297.0
        const val MAX_CER = 0.02
        const val BOX_TOLERANCE = 0.01f
        const val MAX_CANCEL_MS = 1000L
    }
}
