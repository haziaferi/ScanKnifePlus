package com.haziaferi.scanknifeplus.ocr

import android.content.Context
import java.io.File

/** The app's [OcrEngine], on Tesseract. Not thread-safe: one page at a time. Work in progress: reads nothing yet. */
class TesseractOcrEngine private constructor(private val context: Context) : OcrEngine {
    override fun read(page: File, longEdgeMm: Double, language: OcrLanguage, reading: OcrReading): OcrPage? = null

    override fun cancel() = Unit

    override fun close() = Unit

    companion object {
        /** An engine for one job; cheap, since the language data is installed and Tesseract started on the first [read]. */
        fun open(context: Context): OcrEngine = TesseractOcrEngine(context.applicationContext)
    }
}
