package com.haziaferi.scanknifeplus.scan.export

import com.haziaferi.scanknifeplus.ocr.OcrEngine
import com.haziaferi.scanknifeplus.ocr.OcrLanguage
import com.haziaferi.scanknifeplus.ocr.OcrPage
import com.haziaferi.scanknifeplus.ocr.OcrReading
import com.haziaferi.scanknifeplus.ocr.OcrWord
import java.io.File
import java.util.Collections

/** What a [FakeOcrEngine] was asked to read: the page file's text and the settings. */
data class FakeRead(val text: String, val longEdgeMm: Double, val language: OcrLanguage, val reading: OcrReading)

/**
 * An engine that reads a page file's text as one word across the page, with confidence 30 (handwriting) when the text holds "scribble"; [onRead]
 * runs inside each read, before it returns, so a test can act while a page is being read. A [cancel] makes the read in progress return null.
 */
class FakeOcrEngine(var onRead: (String) -> Unit = {}) : OcrEngine {
    val reads: MutableList<FakeRead> = Collections.synchronizedList(mutableListOf())

    @Volatile var cancels = 0
    @Volatile var closes = 0

    @Volatile private var cancelled = false

    override fun read(page: File, longEdgeMm: Double, language: OcrLanguage, reading: OcrReading): OcrPage? {
        val text = page.readText()
        reads += FakeRead(text, longEdgeMm, language, reading)
        cancelled = false
        onRead(text)
        if (cancelled) return null
        return OcrPage(listOf(OcrWord(text, 0.1f, 0.1f, 0.9f, 0.2f, if ("scribble" in text) 30f else 90f, 0, 0, 0)))
    }

    override fun cancel() {
        cancels++
        cancelled = true
    }

    override fun close() {
        closes++
    }
}
