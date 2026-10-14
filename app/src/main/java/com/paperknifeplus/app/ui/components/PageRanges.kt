package com.paperknifeplus.app.ui.components

/** What [PageRanges.parseList] made of a range list: the ranges it names, or the first reason it was refused. */
sealed interface RangeList {
    /** 0-based page [ranges] in the order given; ranges may overlap or repeat. */
    data class Valid(val ranges: List<IntRange>) : RangeList

    /** The text names no pages: it is blank or only commas. */
    data object Empty : RangeList

    /** [part] is not a page number `5`, a range `1-3` or a range to the last page `8-`. */
    data class Malformed(val part: String) : RangeList

    /** [part] names [page] (Int.MAX_VALUE when the number is longer than that), which is not one of the document's [pageCount] pages. */
    data class OutOfBounds(val part: String, val page: Int, val pageCount: Int) : RangeList

    /** [part] runs backwards, from page [start] down to page [end]. */
    data class Reversed(val part: String, val start: Int, val end: Int) : RangeList
}

/** The page-range text the page tools accept, e.g. "1-3, 5, 8-": 1-based pages in, 0-based page indices out. */
object PageRanges {
    // A page, a range or a range to the end, in ASCII digits after asciiDigits; "+3" and "-4" are malformed.
    private val PART = Regex("""(\d+)(?:\s*-\s*(\d*))?""")

    /** One comma-separated part. Pages are 1-based, null when too large for an Int; [end] is null for "8-", [start] for a single page. */
    private class Part(val isRange: Boolean, val start: Int?, val end: Int?, val open: Boolean)

    /** [text] with every decimal digit of any script (Arabic-Indic, Devanagari, full-width...) as its ASCII digit. */
    private fun asciiDigits(text: String): String = buildString {
        for (char in text) {
            val digit = Character.digit(char, 10)
            append(if (digit >= 0) '0' + digit else char)
        }
    }

    /** Reads one trimmed part, or null when it is malformed. */
    private fun readPart(text: String): Part? {
        val match = PART.matchEntire(asciiDigits(text)) ?: return null
        val start = match.groupValues[1].toIntOrNull()
        val isRange = match.groups[2] != null
        val endText = match.groupValues[2]
        return when {
            !isRange -> Part(false, start, start, open = false)
            endText.isEmpty() -> Part(true, start, null, open = true)
            else -> Part(true, start, endText.toIntOrNull(), open = false)
        }
    }

    /**
     * Parses [input] for a document of [max] pages as the Split and Delete screens do while the user types. Each comma-separated part is
     * read on its own, so a malformed part is skipped without losing the others; ranges are clamped to 1..[max] and may run backwards,
     * single pages outside it are ignored, and open ranges ("8-") and signed numbers ("+3") are skipped. Digits of any script count.
     */
    fun parse(input: String, max: Int): Set<Int> {
        if (max < 1) return emptySet()
        val pages = mutableSetOf<Int>()
        for (text in input.split(',')) {
            val part = readPart(text.trim()) ?: continue
            if (part.isRange) {
                val start = part.start?.coerceIn(1, max) ?: continue
                val end = part.end?.coerceIn(1, max) ?: continue
                for (page in minOf(start, end)..maxOf(start, end)) pages.add(page - 1)
            } else {
                val page = part.start ?: continue
                if (page in 1..max) pages.add(page - 1)
            }
        }
        return pages
    }

    /**
     * Parses [input] strictly for a document of [pageCount] pages, as the tools that write one output per range or take a range per file
     * do: a typo must never pick other pages. "8-" runs to the last page, digits of any script count and blank parts are ignored;
     * any other problem refuses the whole list, naming the first part at fault.
     */
    fun parseList(input: String, pageCount: Int): RangeList {
        val ranges = mutableListOf<IntRange>()
        for (raw in input.split(',')) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            val part = readPart(text) ?: return RangeList.Malformed(text)
            val start = part.start ?: Int.MAX_VALUE
            val end = if (part.open) pageCount else part.end ?: Int.MAX_VALUE
            for (page in listOf(start, end)) {
                if (page !in 1..pageCount) return RangeList.OutOfBounds(text, page, pageCount)
            }
            if (start > end) return RangeList.Reversed(text, start, end)
            ranges += (start - 1)..(end - 1)
        }
        return if (ranges.isEmpty()) RangeList.Empty else RangeList.Valid(ranges)
    }

    /** Writes 0-based [pages] back as the shortest range text, e.g. {0, 1, 2, 4} as "1-3, 5". */
    fun format(pages: Set<Int>): String {
        if (pages.isEmpty()) return ""
        val sorted = pages.sorted()
        val parts = mutableListOf<String>()
        fun addRun(start: Int, end: Int) {
            parts += if (start == end) "${start + 1}" else "${start + 1}-${end + 1}"
        }
        var start = sorted[0]
        var prev = start
        for (page in sorted.drop(1)) {
            if (page != prev + 1) {
                addRun(start, prev)
                start = page
            }
            prev = page
        }
        addRun(start, prev)
        return parts.joinToString(", ")
    }
}
