package com.haziaferi.scanknifeplus.ocr

import android.content.Context
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessBaseAPI.PageIteratorLevel
import com.googlecode.tesseract.android.TessBaseAPI.PageSegMode
import com.haziaferi.scanknifeplus.scan.capture.ImageCodec
import com.haziaferi.scanknifeplus.scan.capture.ImageSource
import java.io.File

/**
 * The app's [OcrEngine], on Tesseract (tessdata_fast). Each page is decoded, turned into the contrast-stretched grey copy, scaled to [DPI] of
 * its assumed size, checked for orientation by a separate OSD instance and read by an LSTM instance; both instances live until [close], since
 * starting them costs about 150 ms. Not thread-safe: one [read] at a time, and [close] after it; only [cancel] may come from another thread.
 * Like PageImages, nothing here throws: a page that cannot be read comes back as null.
 */
class TesseractOcrEngine private constructor(private val context: Context) : OcrEngine {
    /** Guards the instances' lifetime against [cancel], which stops a read from another thread. */
    private val lock = Any()

    @Volatile private var cancelled = false
    private var dataPath: String? = null
    private var osd: TessBaseAPI? = null
    private var recogniser: TessBaseAPI? = null
    private var recogniserLanguage: OcrLanguage? = null

    override fun read(page: File, longEdgeMm: Double, language: OcrLanguage, reading: OcrReading): OcrPage? {
        cancelled = false
        return try {
            val path = dataPath ?: TessData.install(context)?.absolutePath?.also { dataPath = it } ?: return null
            val grey = prepared(page, longEdgeMm) ?: return null
            if (cancelled) return null
            val turn = orientation(path, grey) ?: return null
            if (cancelled) return null
            val upright = OcrImages.rotatedClockwise(grey, turn)
            val words = recognised(path, upright, language, reading) ?: return null
            if (cancelled) null else OcrPage(OcrImages.words(words, upright.width, upright.height, turn), turn)
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed on $page", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "OCR ran out of memory on $page", e)
            null
        }
    }

    /** Stops the recognition of a read in progress within a word or so; layout analysis and orientation detection run to their end first. */
    override fun cancel() {
        cancelled = true
        synchronized(lock) { recogniser?.stop() }
    }

    override fun close() {
        synchronized(lock) {
            osd?.recycle()
            recogniser?.recycle()
            osd = null
            recogniser = null
            recogniserLanguage = null
        }
    }

    /** The page as grey at [DPI] of a page [longEdgeMm] long, oriented like the image; null if it cannot be decoded. */
    private fun prepared(page: File, longEdgeMm: Double): GreyImage? {
        val grey = OcrImages.grey(ImageCodec.decodeScaled(ImageSource.of(page), 0) ?: return null)
        val size = OcrImages.targetSize(grey.width, grey.height, longEdgeMm, DPI)
        return OcrImages.resized(grey, size.width, size.height)
    }

    /** The clockwise turn that makes [grey] upright, 0 unless detection is confident; null if the OSD instance cannot start. */
    private fun orientation(path: String, grey: GreyImage): Int? {
        val tess = osd ?: started(path, "osd", TessBaseAPI.OEM_TESSERACT_ONLY)?.also {
            it.setPageSegMode(PageSegMode.PSM_OSD_ONLY)
            synchronized(lock) { osd = it }
        } ?: return null
        tess.setVariable("user_defined_dpi", DPI.toString())
        tess.setImage(grey.pixels, grey.width, grey.height, 1, grey.width)
        val found = tess.detectOrientationScript()
        tess.clear()
        return if (found != null && found.orientationConfidence >= MIN_TURN_CONFIDENCE) found.rotateClockwise else 0
    }

    /** [grey]'s words in reading order; null if the instance cannot start in [language]. */
    private fun recognised(path: String, grey: GreyImage, language: OcrLanguage, reading: OcrReading): List<RawWord>? {
        var tess = recogniser
        if (tess == null) {
            tess = started(path, language.code, TessBaseAPI.OEM_LSTM_ONLY) ?: return null
            synchronized(lock) { recogniser = tess }
            recogniserLanguage = language
        } else if (recogniserLanguage != language) {
            recogniserLanguage = null
            if (!tess.init(path, language.code, TessBaseAPI.OEM_LSTM_ONLY)) {
                Log.w(TAG, "Tesseract init failed for ${language.code}")
                return null
            }
            recogniserLanguage = language
        }
        tess.setVariable("user_defined_dpi", DPI.toString())
        tess.setPageSegMode(reading.pageSegMode)
        tess.setImage(grey.pixels, grey.width, grey.height, 1, grey.width)
        // Recognition through the monitor that getHOCRText passes is the only kind TessBaseAPI.stop() can cancel; the text it returns is unused.
        tess.getHOCRText(0)
        val words = if (cancelled) emptyList() else rawWords(tess)
        tess.clear()
        return words
    }

    private fun rawWords(tess: TessBaseAPI): List<RawWord> {
        val iter = tess.resultIterator ?: return emptyList()
        val words = ArrayList<RawWord>()
        try {
            iter.begin()
            do {
                val text = iter.getUTF8Text(PageIteratorLevel.RIL_WORD) ?: continue
                val box = iter.getBoundingBox(PageIteratorLevel.RIL_WORD)
                words += RawWord(
                    text, iter.confidence(PageIteratorLevel.RIL_WORD), box[0], box[1], box[2], box[3],
                    iter.isAtBeginningOf(PageIteratorLevel.RIL_BLOCK), iter.isAtBeginningOf(PageIteratorLevel.RIL_PARA),
                    iter.isAtBeginningOf(PageIteratorLevel.RIL_TEXTLINE),
                )
            } while (iter.next(PageIteratorLevel.RIL_WORD))
        } finally {
            iter.delete()
        }
        return words
    }

    /** A Tesseract instance initialised with [language] from [path]; null, logged, if init fails. */
    private fun started(path: String, language: String, engineMode: Int): TessBaseAPI? {
        // The progress callback runs on the reading thread during recognition, so a cancel that came before the monitor existed still stops it.
        lateinit var tess: TessBaseAPI
        tess = TessBaseAPI { if (cancelled) tess.stop() }
        if (tess.init(path, language, engineMode)) return tess
        Log.w(TAG, "Tesseract init failed for $language")
        tess.recycle()
        return null
    }

    companion object {
        private const val TAG = "TesseractOcrEngine"

        /** Pages are read at this resolution of their assumed size (questions.md, question 1); Tesseract is told so. */
        const val DPI = 200

        /** Orientation detection's turn is applied only at or above this confidence (question 5: no wrong turn above 1.5; 3 leaves a margin). */
        const val MIN_TURN_CONFIDENCE = 3f

        /** An engine for one job; cheap, since the language data is installed and Tesseract started on the first [read]. */
        fun open(context: Context): OcrEngine = TesseractOcrEngine(context.applicationContext)
    }
}
