package com.paperknifeplus.app.ui.components

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.InputStream

/** One file a split writes: 0-based [pages] of the source and, for a split by bookmarks, the [title] of the bookmark it starts at. */
data class SplitPart(val pages: IntRange, val title: String? = null)

/** The parts [bookmarkParts] cut a document into, and how many bookmarks of the chosen levels were [skipped] for leading to no page. */
data class BookmarkSplit(val parts: List<SplitPart>, val skipped: Int)

/**
 * The parts that split a document of [pageCount] pages with [outline] (as [readOutline] reads it) at its bookmarks of depth 0 to [maxDepth]
 * (0 for the top level only). Each part runs from its bookmark's page to the page before the next bookmark's, in page order, so a bookmark
 * pointing backwards still cuts where its page is; pages before the first bookmark form an untitled part of their own. When bookmarks share
 * a page, the first of them in the outline names the part. Bookmarks whose target is not a page of the document are skipped and counted.
 * No parts when no bookmark leads to a page.
 */
fun bookmarkParts(outline: List<Bookmark>, pageCount: Int, maxDepth: Int = 0): BookmarkSplit {
    val chosen = outline.filter { it.depth <= maxDepth }
    val resolved = chosen.filter { it.pageIndex in 0 until pageCount }
    val starts = resolved.sortedBy { it.pageIndex }.distinctBy { it.pageIndex }
    val skipped = chosen.size - resolved.size
    if (starts.isEmpty()) return BookmarkSplit(emptyList(), skipped)
    val parts = mutableListOf<SplitPart>()
    val first = starts.first().pageIndex!!
    if (first > 0) parts += SplitPart(0 until first)
    starts.forEachIndexed { i, bookmark ->
        val end = starts.getOrNull(i + 1)?.pageIndex ?: pageCount
        parts += SplitPart(bookmark.pageIndex!! until end, bookmark.title)
    }
    return BookmarkSplit(parts, skipped)
}

/**
 * Writes each of [parts] of a PDF as its own document. Each part is a fresh copy of the source, opened from [open] with [password] (empty
 * for none) in [memory], reduced to its pages in place as [keepPages] does, so outline items, links, form fields, named destinations and
 * page labels that stay inside the part keep working and nothing outside it is written. It keeps the source's protection
 * ([sameProtection]), and [write] saves it (it is closed afterwards). Blocking; throws on failure, after the parts already written.
 */
fun splitPdf(
    open: () -> InputStream,
    password: String,
    parts: List<SplitPart>,
    memory: MemoryUsageSetting = MemoryUsageSetting.setupTempFileOnly(),
    onProgress: (Int, Int) -> Unit = { _, _ -> },
    write: (index: Int, part: SplitPart, document: PDDocument) -> Unit
) {
    parts.forEachIndexed { index, part ->
        onProgress(index + 1, parts.size)
        open().use { input ->
            PDDocument.load(input, password, memory).use { document ->
                val protection = document.sameProtection(password)
                document.keepPages(part.pages.toList())
                protection?.let(document::protect)
                write(index, part, document)
            }
        }
    }
}

/** The 1-based [number] of a part padded to the digits of [count], so the files sort in order: 3 of 120 is "003". */
private fun partNumber(number: Int, count: Int): String = number.toString().padStart(maxOf(2, count.toString().length), '0')

/** The file name of bookmark part [index] (0-based) of [count]: `NN_<title>.pdf`, or `NN_<baseName>.pdf` for the untitled first part. */
fun bookmarkPartFileName(baseName: String, index: Int, count: Int, title: String?): String =
    outputFileName("${partNumber(index + 1, count)}_${title?.trim()?.ifEmpty { null } ?: baseName}", "pdf")

/** The file name of range part [index] (0-based) of [count], 0-based [pages] of [baseName]: `<baseName>_partNN_pA-B.pdf`. */
fun rangePartFileName(baseName: String, index: Int, count: Int, pages: IntRange): String =
    outputFileName("${baseName}_part${partNumber(index + 1, count)}_p${pages.first + 1}-${pages.last + 1}", "pdf")
