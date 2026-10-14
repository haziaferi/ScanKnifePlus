package com.paperknifeplus.app.ui.components

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import java.io.InputStream
import java.io.OutputStream

/** One file of a merge: [open] reads it, and [ranges] (from [PageRanges.parseList]) are its pages to take in that order, null for all. */
class MergeSource(val open: () -> InputStream, val ranges: List<IntRange>? = null)

/**
 * Reduces [document] to the pages of [ranges] in that order, in place as [keepPages] does. A page named more than once is repeated as a
 * copy sharing its content, since one page object cannot sit in the page tree twice.
 */
internal fun PDDocument.keepRanges(ranges: List<IntRange>) {
    val seen = HashSet<Int>()
    val order = ranges.flatten().map { index ->
        if (seen.add(index)) {
            index
        } else {
            addPage(PDPage(COSDictionary(getPage(index).cosObject)))
            numberOfPages - 1
        }
    }
    keepPages(order)
}

/**
 * Appends [sources] in order into one PDF written to [output] with PDFBox's merger, which carries over outlines, forms, named destinations
 * and page labels; a source with ranges is first reduced to them ([keepRanges]). Sources must open without a password (the Merge screen
 * passes decrypted copies) and are read into [memory]. Returns the page count. Blocking; throws on failure.
 */
fun mergePdfs(
    sources: List<MergeSource>,
    output: OutputStream,
    memory: MemoryUsageSetting = MemoryUsageSetting.setupTempFileOnly(),
    onProgress: (Int, Int) -> Unit = { _, _ -> }
): Int {
    // Sources stay open until the result is saved, as PDFMergerUtility.mergeDocuments keeps them.
    val opened = mutableListOf<PDDocument>()
    try {
        PDDocument(memory).use { destination ->
            val merger = PDFMergerUtility()
            sources.forEachIndexed { index, source ->
                onProgress(index + 1, sources.size)
                val document = source.open().use { PDDocument.load(it, memory) }
                opened += document
                source.ranges?.let(document::keepRanges)
                merger.appendDocument(destination, document)
            }
            destination.save(output)
            return destination.numberOfPages
        }
    } finally {
        opened.forEach(PDDocument::close)
    }
}
